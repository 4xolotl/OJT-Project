package com.ojt.board.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ojt.board.authorization.AuthorizationDenialAudit;
import com.ojt.board.authorization.ObjectAuthorizationDeniedException;
import com.ojt.board.authorization.ObjectAuthorizationOperation;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {

    @Test
    void auditFailureDoesNotChangeDenialAndIsReportedAgainAfterRecovery(CapturedOutput output) {
        AuthorizationDenialAudit audit = mock(AuthorizationDenialAudit.class);
        ObjectAuthorizationDeniedException denial = new ObjectAuthorizationDeniedException(
                8L, 1L, ObjectAuthorizationOperation.POST_UPDATE);
        doThrow(new IllegalStateException("first failure"))
                .doNothing()
                .doThrow(new IllegalStateException("second failure"))
                .when(audit).record(any(ObjectAuthorizationDeniedException.class));
        ApiExceptionHandler handler = new ApiExceptionHandler(audit);

        ResponseEntity<Map<String, String>> first = handler.handleObjectAuthorizationDenied(denial);
        ResponseEntity<Map<String, String>> recovered = handler.handleObjectAuthorizationDenied(denial);
        ResponseEntity<Map<String, String>> failedAgain = handler.handleObjectAuthorizationDenied(denial);

        assertForbidden(first);
        assertForbidden(recovered);
        assertForbidden(failedAgain);
        verify(audit, times(3)).record(denial);
        assertEquals(2, occurrences(output.toString(), "객체 권한 거부 감사 기록에 실패했습니다."));
        assertThat(output).contains("first failure", "second failure");
    }

    @Test
    void successForAnotherOperationDoesNotResetFailureSuppression(CapturedOutput output) {
        AuthorizationDenialAudit audit = mock(AuthorizationDenialAudit.class);
        doAnswer(invocation -> {
            ObjectAuthorizationDeniedException denial = invocation.getArgument(0);
            if (denial.operation() == ObjectAuthorizationOperation.POST_UPDATE) {
                throw new IllegalStateException("persistent post audit failure");
            }
            return null;
        }).when(audit).record(any(ObjectAuthorizationDeniedException.class));
        ApiExceptionHandler handler = new ApiExceptionHandler(audit);
        ObjectAuthorizationDeniedException postDenial = new ObjectAuthorizationDeniedException(
                8L, 1L, ObjectAuthorizationOperation.POST_UPDATE);
        ObjectAuthorizationDeniedException commentDenial = new ObjectAuthorizationDeniedException(
                8L, 2L, ObjectAuthorizationOperation.COMMENT_DELETE);

        handler.handleObjectAuthorizationDenied(postDenial);
        handler.handleObjectAuthorizationDenied(commentDenial);
        handler.handleObjectAuthorizationDenied(postDenial);

        assertEquals(1, occurrences(output.toString(), "객체 권한 거부 감사 기록에 실패했습니다."));
    }

    private void assertForbidden(ResponseEntity<Map<String, String>> response) {
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("게시글 작성자만 수정하거나 삭제할 수 있습니다.",
                response.getBody().get("message"));
    }

    private int occurrences(String value, String fragment) {
        return (value.length() - value.replace(fragment, "").length()) / fragment.length();
    }
}

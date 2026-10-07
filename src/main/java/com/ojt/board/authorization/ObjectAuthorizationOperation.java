package com.ojt.board.authorization;

public enum ObjectAuthorizationOperation {

    POST_UPDATE("update", "post", "게시글 작성자만 수정하거나 삭제할 수 있습니다."),
    POST_DELETE("delete", "post", "게시글 작성자만 수정하거나 삭제할 수 있습니다."),
    COMMENT_UPDATE("update", "comment", "댓글 작성자만 수정하거나 삭제할 수 있습니다."),
    COMMENT_DELETE("delete", "comment", "댓글 작성자만 수정하거나 삭제할 수 있습니다."),
    ATTACHMENT_UPLOAD("upload", "post", "게시글 작성자만 수정하거나 삭제할 수 있습니다."),
    ATTACHMENT_DELETE("delete", "attachment", "게시글 작성자만 수정하거나 삭제할 수 있습니다.");

    private final String action;
    private final String resourceType;
    private final String deniedMessage;

    ObjectAuthorizationOperation(String action, String resourceType, String deniedMessage) {
        this.action = action;
        this.resourceType = resourceType;
        this.deniedMessage = deniedMessage;
    }

    public String action() {
        return action;
    }

    public String resourceType() {
        return resourceType;
    }

    public String deniedMessage() {
        return deniedMessage;
    }
}

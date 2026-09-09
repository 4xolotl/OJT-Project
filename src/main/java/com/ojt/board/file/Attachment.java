package com.ojt.board.file;

import com.ojt.board.post.Post;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.MediaType;

@Entity
@Table(name = "attachments", indexes = @Index(name = "idx_attachment_post", columnList = "post_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @Column(nullable = false, length = 255)
    private String originalFilename;

    @Column(nullable = false, unique = true, length = 36)
    private String storedFilename;

    @Column(name = "file_size", nullable = false)
    private long size;

    @Column(nullable = false, length = 100)
    private String contentType;

    public Attachment(Post post, String originalFilename, String storedFilename, long size) {
        this.post = post;
        this.originalFilename = originalFilename;
        this.storedFilename = storedFilename;
        this.size = size;
        // Storage keeps the original bytes independently of the browser's media type.
        this.contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }
}

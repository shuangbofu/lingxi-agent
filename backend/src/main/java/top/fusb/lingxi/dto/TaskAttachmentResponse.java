package top.fusb.lingxi.dto;

import lombok.Data;

@Data
public class TaskAttachmentResponse {

    private String id;
    private String name;
    private String contentType;
    private Long size;
    private String url;
    private String inputKind;
}

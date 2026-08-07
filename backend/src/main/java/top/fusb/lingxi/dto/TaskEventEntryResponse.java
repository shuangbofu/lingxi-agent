package top.fusb.lingxi.dto;

import lombok.Data;

import java.util.List;

@Data
public class TaskEventEntryResponse {

    private String type;
    private String text;
    private String state;
    private Integer count;
    private List<String> details;
}

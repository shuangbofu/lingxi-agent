package top.fusb.lingxi.dto;

import lombok.Data;

import java.util.List;

@Data
public class ModelEndpointCheckResponse {

    private boolean success;

    private String message;

    private List<String> models;
}

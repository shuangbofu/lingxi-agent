package top.fusb.lingxi.dto;

import lombok.Data;

@Data
public class ModelEndpointCheckRequest {

    private String modelProfileId;

    private String providerId;

    private String apiKey;

    private String baseUrl;
}

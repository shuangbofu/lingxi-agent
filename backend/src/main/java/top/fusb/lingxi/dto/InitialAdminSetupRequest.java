package top.fusb.lingxi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class InitialAdminSetupRequest {

    @NotBlank(message = "请设置管理员密码")
    @Size(min = 8, max = 128, message = "密码长度需要为 8 至 128 位")
    private String password;
}

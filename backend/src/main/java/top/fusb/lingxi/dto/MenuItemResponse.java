package top.fusb.lingxi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MenuItemResponse {

    private String code;
    private String label;
    private String shortLabel;
    private String path;
    private String icon;
    private String tone;
    private String permission;
}

package org.rama.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Base64File {
    private String fileName;
    private String base64String;

    public static Base64File of(String base64String) {
        return new Base64File(null, base64String);
    }

    public static Base64File of(String fileName, String base64String) {
        return new Base64File(fileName, base64String);
    }
}

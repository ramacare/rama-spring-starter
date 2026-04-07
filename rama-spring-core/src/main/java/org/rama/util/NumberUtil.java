package org.rama.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class NumberUtil {
    private NumberUtil() {
    }

    public static BigDecimal toBigDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Integer i) return new BigDecimal(i);
        if (o instanceof Long l) return new BigDecimal(l);
        if (o instanceof Double d) return BigDecimal.valueOf(d);
        String s = o.toString().trim();
        return s.isEmpty() ? BigDecimal.ZERO : new BigDecimal(s);
    }

    public static String bahtText(Number number) {
        return bahtText(number.toString());
    }

    public static String bahtText(String strNum) {
        if (strNum == null) {
            return "";
        }

        strNum = strNum.replaceAll("^[+-]?", "").replaceAll("[,\\s฿]", "");

        if (!strNum.matches("^\\d+(\\.\\d+)?$")) {
            return "";
        }

        BigDecimal bdNum = new BigDecimal(strNum).setScale(2, RoundingMode.HALF_EVEN);

        if (bdNum.compareTo(BigDecimal.ZERO) == 0) {
            return "ศูนย์บาทถ้วน";
        }

        boolean isOnlyInteger = bdNum.stripTrailingZeros().scale() <= 0;
        String[] parts = bdNum.toPlainString().split("\\.", 0);
        StringBuilder strBahtText = new StringBuilder();

        if (!parts[0].equals("0")) {
            strBahtText.append(changeNumberToWord(parts[0], isOnlyInteger ? "บาทถ้วน" : "บาท"));
        }
        if (!isOnlyInteger) {
            strBahtText.append(" ").append(changeNumberToWord(parts[1], "สตางค์"));
        }
        return strBahtText.toString();
    }

    private static String changeNumberToWord(String strValue, String suffix) {
        String[] weight = {"", "สิบ", "ร้อย", "พัน", "หมื่น", "แสน", "ล้าน"};
        String[] pn = {"", "หนึ่ง", "สอง", "สาม", "สี่", "ห้า", "หก", "เจ็ด", "แปด", "เก้า"};
        StringBuilder result = new StringBuilder();
        int length = strValue.length();

        for (int i = 0; i < length; i++) {
            int n = Character.getNumericValue(strValue.charAt(i));
            if (n > 0) {
                String numeral = pn[n];
                if (n == 1) {
                    if (i == length - 1 && length > 1) {
                        numeral = (suffix.equals("สตางค์") && strValue.charAt(length - 2) == '0') ? "หนึ่ง" : "เอ็ด";
                    } else if (i == length - 2) {
                        numeral = "";
                    }
                } else if (n == 2 && i == length - 2) {
                    numeral = "ยี่";
                }
                result.append(numeral).append(weight[length - i - 1]);
            }
        }
        return result.append(suffix).toString();
    }
}

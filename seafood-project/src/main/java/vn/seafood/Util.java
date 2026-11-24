package vn.seafood;

import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class Util {
  public static void need(boolean c, String m) {
    if (!c) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
  }

  public static String str(Map<String, Object> b, String k) {
    return Objects.toString(b.get(k), "").trim();
  }

  public static String required(Map<String, Object> b, String k) {
    String s = str(b, k);
    need(!s.isBlank(), "Thiếu thông tin: " + k);
    need(s.length() <= 10000, "Nội dung quá dài");
    return s;
  }

  public static long id(Map<String, Object> b, String k) {
    try {
      return Long.parseLong(str(b, k));
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã không hợp lệ: " + k);
    }
  }

  public static Long optionalId(Map<String, Object> b, String k) {
    return str(b, k).isBlank() ? null : id(b, k);
  }

  public static BigDecimal dec(Map<String, Object> b, String k) {
    try {
      BigDecimal n = new BigDecimal(str(b, k));
      need(n.scale() <= 3, "Tối đa 3 chữ số thập phân: " + k);
      return n;
    } catch (NumberFormatException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Số không hợp lệ: " + k);
    }
  }

  public static BigDecimal n(Object x) {
    return x == null ? BigDecimal.ZERO : new BigDecimal(x.toString());
  }

  public static int integer(Map<String, Object> b, String k) {
    try {
      return dec(b, k).intValueExact();
    } catch (ArithmeticException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phải là số nguyên: " + k);
    }
  }

  public static boolean same(Object a, Object b) {
    return a != null && b != null && a.toString().equals(b.toString());
  }

  public static boolean yes(Map<String, Object> b, String k) {
    return Boolean.TRUE.equals(b.get(k)) || "true".equals(str(b, k));
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> list(Map<String, Object> b, String k) {
    need(b.get(k) instanceof List, "Thiếu danh sách: " + k);
    return (List<Map<String, Object>>) b.get(k);
  }

  public static String code(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
  }

  public static void role(Map<String, Object> u, String... roles) {
    if (!Arrays.asList(roles).contains(u.get("role")))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền thao tác");
  }

  public static void own(Map<String, Object> u, Object wh) {
    role(u, "WAREHOUSE");
    if (!same(u.get("warehouse_id"), wh))
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Chỉ được thao tác tại kho được phân công");
  }

  public static void date(String x) {
    try {
      LocalDate.parse(x);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ngày phải theo YYYY-MM-DD");
    }
  }

  public static boolean containerCode(String s) {
    if (!s.matches("[A-Z]{3}U[0-9]{7}")) return false;
    int sum = 0;
    for (int i = 0; i < 10; i++) {
      char c = s.charAt(i);
      int v = Character.isDigit(c) ? c - '0' : 10 + (c - 'A') + (c - 'A' + 9) / 10;
      sum += v * (1 << i);
    }
    return (sum % 11) % 10 == s.charAt(10) - '0';
  }
}

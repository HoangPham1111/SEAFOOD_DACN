package vn.seafood;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import java.awt.Color;
import java.io.*;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PdfService {
  final Engine e;

  public PdfService(Engine e) {
    this.e = e;
  }

  static final Map<String, String> VI =
      Map.ofEntries(
          Map.entry("REQUEST", "PHIẾU YÊU CẦU"),
          Map.entry("TRIP_ORDER", "LỆNH VẬN CHUYỂN"),
          Map.entry("WAREHOUSE_IN", "PHIẾU NHẬP KHO"),
          Map.entry("WAREHOUSE_OUT", "PHIẾU XUẤT KHO"),
          Map.entry("HANDOVER", "BIÊN BẢN GIAO NHẬN"),
          Map.entry("DISCREPANCY", "BIÊN BẢN SAI LỆCH"),
          Map.entry("COUNT", "BIÊN BẢN KIỂM KÊ"),
          Map.entry("CONTRACT", "HỢP ĐỒNG THƯƠNG MẠI"),
          Map.entry("INVOICE", "HÓA ĐƠN THƯƠNG MẠI"),
          Map.entry("PACKING", "PHIẾU ĐÓNG GÓI"));
  static final Map<String, String> EN =
      Map.ofEntries(
          Map.entry("CONTRACT", "SALES CONTRACT"),
          Map.entry("INVOICE", "COMMERCIAL INVOICE"),
          Map.entry("PACKING", "PACKING LIST"),
          Map.entry("BILL_OF_LADING", "BILL OF LADING RECORD"),
          Map.entry("ORIGIN", "CERTIFICATE OF ORIGIN RECORD"),
          Map.entry("CUSTOMS", "CUSTOMS CLEARANCE RECORD"),
          Map.entry("QUARANTINE", "QUARANTINE RESULT RECORD"),
          Map.entry("FOOD_SAFETY", "FOOD SAFETY RECORD"));

  @SuppressWarnings("unchecked")
  public byte[] render(Map<String, Object> d) throws Exception {
    boolean en = "EN".equals(d.get("language"));
    Map<String, Object> s = e.parse(d.get("snapshot")),
        f = e.parse(d.get("fields")),
        o = (Map<String, Object>) s.get("operation");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Document doc = new Document(PageSize.A4, 36, 36, 32, 36);
    PdfWriter.getInstance(doc, out);
    doc.open();
    BaseFont bf =
        BaseFont.createFont(
            "DejaVuSans.ttf",
            BaseFont.IDENTITY_H,
            BaseFont.EMBEDDED,
            false,
            getClass().getResourceAsStream("/fonts/DejaVuSans.ttf").readAllBytes(),
            null);
    Font normal = new Font(bf, 9),
        title = new Font(bf, 17, Font.BOLD, new Color(10, 88, 100)),
        bold = new Font(bf, 10, Font.BOLD);
    for (var setting : (List<Map<String, Object>>) s.get("company")) {
      String k = setting.get("key").toString();
      if (k.equals(en ? "company_name_en" : "company_name")
          || k.equals(en ? "company_address_en" : "company_address"))
        doc.add(new Paragraph(setting.get("value").toString(), bold));
    }
    Paragraph head =
        new Paragraph(
            (en ? EN : VI).getOrDefault(d.get("kind").toString(), d.get("kind").toString()), title);
    head.setSpacingBefore(18);
    head.setSpacingAfter(12);
    doc.add(head);
    doc.add(new Paragraph((en ? "Document No.: " : "Số chứng từ: ") + d.get("number"), bold));
    doc.add(new Paragraph((en ? "Operation: " : "Yêu cầu: ") + o.get("code"), normal));
    doc.add(
        new Paragraph(
            (en ? "Route: " : "Tuyến: ")
                + o.get("origin_place")
                + " → "
                + o.get("destination_place"),
            normal));
    Map<String, String> labels =
        Map.ofEntries(
            Map.entry("issuer", en ? "Issued by" : "Người lập"),
            Map.entry("issue_date", en ? "Issue date" : "Ngày lập"),
            Map.entry("note", en ? "Notes" : "Ghi chú"),
            Map.entry("seller", en ? "Seller" : "Bên bán"),
            Map.entry("buyer", en ? "Buyer" : "Bên mua"),
            Map.entry("seller_address", en ? "Seller address" : "Địa chỉ bên bán"),
            Map.entry("buyer_address", en ? "Buyer address" : "Địa chỉ bên mua"),
            Map.entry("currency", en ? "Currency" : "Tiền tệ"),
            Map.entry("incoterms", "Incoterms"),
            Map.entry("payment_terms", en ? "Payment terms" : "Điều khoản thanh toán"),
            Map.entry("loading_port", en ? "Loading port" : "Cảng xếp"),
            Map.entry("discharge_port", en ? "Discharge port" : "Cảng dỡ"),
            Map.entry("delivery_date", en ? "Delivery date" : "Ngày giao"),
            Map.entry("insurance", en ? "Insurance" : "Bảo hiểm"),
            Map.entry("claims", en ? "Claims" : "Khiếu nại"),
            Map.entry("penalties", en ? "Penalties" : "Chế tài"));
    for (var field : f.entrySet())
      doc.add(
          new Paragraph(
              labels.getOrDefault(field.getKey(), field.getKey()) + ": " + field.getValue(),
              normal));
    if (s.get("trip") instanceof Map<?, ?> trip) {
      doc.add(new Paragraph((en ? "Trip: " : "Chuyến xe: ") + trip.get("code"), bold));
      for (String k : List.of("container", "tractor", "trailer", "driver")) {
        Map<String, Object> r = (Map<String, Object>) trip.get(k);
        doc.add(
            new Paragraph(
                (en
                        ? k
                        : Map.of(
                                "container",
                                "Container",
                                "tractor",
                                "Đầu kéo",
                                "trailer",
                                "Sơ-mi rơ-moóc",
                                "driver",
                                "Tài xế")
                            .get(k))
                    + ": "
                    + r.get("code")
                    + " / "
                    + r.get("name"),
                normal));
      }
    }
    boolean received = List.of("WAREHOUSE_IN", "HANDOVER").contains(d.get("kind"));
    List<Map<String, Object>> lines =
        (List<Map<String, Object>>)
            s.get(received ? "receipts" : s.containsKey("trip_lines") ? "trip_lines" : "lines");
    PdfPTable table = new PdfPTable(new float[] {1.3f, 3f, 1.4f, 1.3f, 1f, 1.3f});
    table.setWidthPercentage(100);
    table.setSpacingBefore(15);
    table.setHeaderRows(1);
    for (String label :
        en
            ? List.of("SKU", "Goods", "Lot", "Kg", "Boxes", "Unit price*")
            : List.of("SKU", "Tên hàng", "Lô", "Kg", "Thùng", "Đơn giá*")) {
      PdfPCell cell = new PdfPCell(new Phrase(label, bold));
      cell.setBackgroundColor(new Color(223, 241, 240));
      cell.setPadding(6);
      table.addCell(cell);
    }
    java.math.BigDecimal total = java.math.BigDecimal.ZERO;
    for (var l : lines) {
      Object kg = l.get(received ? "actual" : "kg");
      for (Object x :
          List.of(
              l.getOrDefault("sku", ""),
              l.getOrDefault(en ? "name_en" : "name", ""),
              java.util.Objects.toString(l.get("lot"), "—"),
              java.util.Objects.toString(kg, ""),
              l.getOrDefault("boxes", 0),
              l.getOrDefault("price", "—"))) {
        PdfPCell cell = new PdfPCell(new Phrase(x.toString(), normal));
        cell.setPadding(5);
        table.addCell(cell);
      }
      if (l.get("price") != null) total = total.add(Util.n(l.get("price")).multiply(Util.n(kg)));
    }
    doc.add(table);
    if (!s.containsKey("trip_lines"))
      doc.add(
          new Paragraph(
              (en ? "Goods value (reference): " : "Giá trị hàng (tham khảo): ")
                  + total.toPlainString()
                  + " "
                  + f.getOrDefault("currency", ""),
              bold));
    doc.add(
        new Paragraph(
            en
                ? "* Reference values only. This record does not replace documents issued by"
                      + " customs, quarantine authorities or carriers."
                : "* Giá trị tham khảo. Phiếu nội bộ không thay thế chứng từ do cơ quan có thẩm"
                      + " quyền phát hành.",
            normal));
    Paragraph sign =
        new Paragraph(
            en
                ? "\n"
                      + "Prepared by                         Received by                        "
                      + " Approved by"
                : "\n"
                      + "Người lập phiếu                      Người giao/nhận                     "
                      + " Người duyệt",
            bold);
    sign.setSpacingBefore(20);
    doc.add(sign);
    doc.close();
    return out.toByteArray();
  }
}

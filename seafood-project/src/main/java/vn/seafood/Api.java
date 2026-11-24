package vn.seafood;

import static vn.seafood.Util.*;

import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class Api {
  final Engine e;
  final JdbcTemplate db;
  final PdfService pdf;

  public Api(Engine e, PdfService p) {
    this.e = e;
    this.db = e.jdbc();
    this.pdf = p;
  }

  Map<String, Object> user(HttpSession s) {
    Object id = s.getAttribute("uid");
    if (id == null)
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập");
    var u =
        db.queryForMap(
            "select id,name,email,role,warehouse_id,status from app_user where id=?", id);
    if (!"ACTIVE".equals(u.get("status")))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản chưa hoạt động");
    return u;
  }

  @GetMapping("/session")
  Object session(HttpSession s) {
    if (s.getAttribute("csrf") == null) s.setAttribute("csrf", UUID.randomUUID().toString());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("csrf", s.getAttribute("csrf"));
    result.put("user", s.getAttribute("uid") == null ? null : user(s));
    return result;
  }

  @PostMapping("/login")
  Object login(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    String email = required(b, "email").toLowerCase();
    var users = db.queryForList("select * from app_user where email=?", email);
    need(
        users.size() == 1
            && e.encoder()
                .matches(required(b, "password"), users.get(0).get("password").toString()),
        "Sai tài khoản hoặc mật khẩu");
    need("ACTIVE".equals(users.get(0).get("status")), "Tài khoản chờ duyệt hoặc bị khóa");
    req.changeSessionId();
    req.getSession().setAttribute("uid", users.get(0).get("id"));
    return session(req.getSession());
  }

  @PostMapping("/logout")
  void logout(HttpSession s) {
    s.invalidate();
  }

  @PostMapping("/register")
  @Transactional
  Object register(@RequestBody Map<String, Object> b) {
    String email = required(b, "email").toLowerCase();
    need(email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"), "Email không hợp lệ");
    need(required(b, "password").length() >= 8, "Mật khẩu tối thiểu 8 ký tự");
    need(
        Set.of("WAREHOUSE", "DISPATCH").contains(str(b, "role")),
        "Chỉ đăng ký nhân viên kho hoặc điều phối");
    db.update(
        "insert into app_user(email,password,name,role,warehouse_id) values(?,?,?,?,?)",
        email,
        e.encoder().encode(str(b, "password")),
        required(b, "name"),
        str(b, "role"),
        optionalId(b, "warehouse_id"));
    return Map.of("message", "Đã gửi đăng ký; chờ quản trị viên duyệt");
  }

  @GetMapping("/public/warehouses")
  Object publicWarehouses() {
    return db.queryForList("select id,name from warehouse where active order by id");
  }

  @PostMapping("/command/{action}")
  Object command(@PathVariable String action, @RequestBody Map<String, Object> b, HttpSession s) {
    return e.command(action, b, user(s));
  }

  static final Map<String, String> QUERIES =
      Map.ofEntries(
          Map.entry(
              "warehouse",
              "select"
                  + " w.id,w.code,w.name,w.address,w.capacity,w.temperature,w.active,ST_X(w.location)"
                  + " lon,ST_Y(w.location) lat,c.used,c.promised,w.capacity-c.used-c.promised free"
                  + " from warehouse w join capacity_view c on c.id=w.id"),
          Map.entry("sku", "select * from sku"),
          Map.entry("partner", "select * from partner"),
          Map.entry(
              "lot",
              "select l.*,k.code sku,k.name,p.name supplier from lot l join sku k on k.id=l.sku_id"
                  + " join partner p on p.id=l.supplier_id"),
          Map.entry("stock", "select * from stock_view"),
          Map.entry(
              "demand",
              "select d.*,w.name warehouse,k.name sku,u.name creator from demand d join warehouse w"
                  + " on w.id=d.warehouse_id join sku k on k.id=d.sku_id join app_user u on"
                  + " u.id=d.created_by"),
          Map.entry(
              "operation",
              "select o.*,u.name creator,(select sum(kg) from operation_line where"
                  + " operation_id=o.id) kg,(select sum(boxes) from operation_line where"
                  + " operation_id=o.id) boxes from operation o join app_user u on"
                  + " u.id=o.created_by"),
          Map.entry(
              "batch",
              "select b.*,o.code operation,o.kind from batch b join operation o on"
                  + " o.id=b.operation_id"),
          Map.entry(
              "trip",
              "select t.*,c.code container,v.code tractor,r.code trailer,d.name"
                  + " driver,b.operation_id,o.kind operation_kind from trip t join resource c on"
                  + " c.id=t.container_id join resource v on v.id=t.tractor_id join resource r on"
                  + " r.id=t.trailer_id join resource d on d.id=t.driver_id left join batch b on"
                  + " b.id=t.batch_id left join operation o on o.id=b.operation_id"),
          Map.entry(
              "resource",
              "select r.*,p.name supplier from resource r left join partner p on"
                  + " p.id=r.partner_id"),
          Map.entry(
              "document",
              "select"
                  + " id,operation_id,batch_id,trip_id,kind,number,language,status,fields,created_by,created_at,(select"
                  + " count(*) from attachment a where a.document_id=d.id) attachments from"
                  + " document d"),
          Map.entry(
              "receipt",
              "select r.*,t.code trip,b.operation_id from receipt r join trip t on t.id=r.trip_id"
                  + " join batch b on b.id=t.batch_id"),
          Map.entry(
              "discrepancy",
              "select d.*,rl.actual,rl.boxes,rl.blocked,rl.blocked_boxes,tl.kg expected_kg,tl.boxes"
                  + " expected_boxes,r.warehouse_id,r.trip_id,b.operation_id from discrepancy d"
                  + " join receipt_line rl on rl.id=d.receipt_line_id join receipt r on"
                  + " r.id=rl.receipt_id join trip_line tl on tl.id=rl.trip_line_id join trip t on"
                  + " t.id=r.trip_id join batch b on b.id=t.batch_id"),
          Map.entry(
              "count",
              "select c.*,s.warehouse_id,l.code lot,w.name warehouse from stock_count c join stock"
                  + " s on s.id=c.stock_id join lot l on l.id=s.lot_id join warehouse w on"
                  + " w.id=s.warehouse_id"),
          Map.entry("return", "select * from goods_return"),
          Map.entry(
              "movement",
              "select m.*,w.name warehouse,l.code lot,u.name actor from movement m join warehouse w"
                  + " on w.id=m.warehouse_id join lot l on l.id=m.lot_id join app_user u on"
                  + " u.id=m.actor_id"),
          Map.entry(
              "position",
              "select p.id,p.trip_id,ST_X(p.location) lon,ST_Y(p.location)"
                  + " lat,p.recorded_at,p.source,p.note,p.actor_id,t.container_id,c.code container"
                  + " from position p join trip t on t.id=p.trip_id join resource c on"
                  + " c.id=t.container_id"),
          Map.entry(
              "user", "select id,email,name,role,warehouse_id,status,created_at from app_user"),
          Map.entry(
              "audit",
              "select a.*,u.name actor from audit a left join app_user u on u.id=a.actor_id"));

  List<Map<String, Object>> data(String name, Map<String, Object> u) {
    need(QUERIES.containsKey(name), "Danh mục không hỗ trợ");
    if (Set.of("user", "audit").contains(name)) role(u, "ADMIN");
    return db.queryForList(QUERIES.get(name) + " order by id desc limit 10000");
  }

  Object clean(Object value) {
    if (value instanceof Map<?, ?> m) {
      Map<String, Object> v = new LinkedHashMap<>();
      m.forEach((k, x) -> v.put(k.toString(), clean(x)));
      return v;
    }
    if (value instanceof List<?> l) return l.stream().map(this::clean).toList();
    if (value != null && value.getClass().getName().equals("org.postgresql.util.PGobject")) {
      try {
        return e.mapper().readTree(value.toString());
      } catch (Exception ex) {
        return value.toString();
      }
    }
    return value;
  }

  @GetMapping("/data/{name}")
  Object data(@PathVariable String name, HttpSession s) {
    return clean(data(name, user(s)));
  }

  @GetMapping("/settings")
  Object settings(HttpSession s) {
    user(s);
    return db.queryForList("select * from setting order by key");
  }

  @GetMapping("/detail/{type}/{id}")
  Object detail(@PathVariable String type, @PathVariable long id, HttpSession s) {
    user(s);
    Map<String, Object> r = new LinkedHashMap<>();
    if (type.equals("operation")) {
      r.put("record", e.row("operation", id));
      r.put(
          "lines",
          db.queryForList(
              "select l.*,k.name,k.code sku from operation_line l join sku k on k.id=l.sku_id where"
                  + " operation_id=? order by l.id",
              id));
      r.put(
          "allocations",
          db.queryForList(
              "select a.*,lo.code lot,k.code sku,k.name,a.kg-coalesce((select sum(tl.kg) from"
                  + " trip_line tl join trip t on t.id=tl.trip_id where tl.allocation_id=a.id and"
                  + " t.status<>'CANCELLED'),0) unassigned_kg,a.boxes-coalesce((select"
                  + " sum(tl.boxes) from trip_line tl join trip t on t.id=tl.trip_id where"
                  + " tl.allocation_id=a.id and t.status<>'CANCELLED'),0) unassigned_boxes from"
                  + " allocation a join operation_line l on l.id=a.line_id join lot lo on"
                  + " lo.id=a.lot_id join sku k on k.id=lo.sku_id where l.operation_id=? order by"
                  + " a.id",
              id));
      r.put("batches", db.queryForList("select * from batch where operation_id=?", id));
      r.put(
          "documents",
          db.queryForList(
              "select id,number,kind,status,language from document where operation_id=?", id));
    } else if (type.equals("trip")) {
      r.put("record", e.row("trip", id));
      r.put("lines", e.tripLines(id));
      r.put(
          "receipts",
          db.queryForList(
              "select rl.* from receipt_line rl join receipt r on r.id=rl.receipt_id where"
                  + " r.trip_id=?",
              id));
    } else if (type.equals("document")) {
      r.put("record", e.row("document", id));
      r.put(
          "attachments",
          db.queryForList(
              "select id,name,mime,sha256,created_at from attachment where document_id=?", id));
    } else if (type.equals("receipt")) {
      r.put("record", e.row("receipt", id));
      r.put("lines", db.queryForList("select * from receipt_line where receipt_id=?", id));
    } else throw new IllegalArgumentException("Loại chi tiết không hỗ trợ");
    return clean(r);
  }

  @GetMapping("/dashboard")
  Object dashboard(HttpSession s) {
    user(s);
    return Map.of(
        "totals",
        db.queryForMap(
            "select sum(kg) kg,sum(available) available,sum(blocked) blocked,sum(reserved) reserved"
                + " from stock_view"),
        "warehouses",
        db.queryForList(QUERIES.get("warehouse")),
        "pending",
        db.queryForObject("select count(*) from operation where status='PENDING'", Integer.class),
        "trips",
        db.queryForObject(
            "select count(*) from trip where status in ('MOVING','ARRIVED','RECEIVING')",
            Integer.class),
        "expiring",
        db.queryForObject(
            "select count(*) from stock_view where kg>0 and expires<=current_date+warning_days",
            Integer.class),
        "in_transit",
        db.queryForList(
            "select o.id,o.code,o.kind,o.destination_id,sum(tl.kg) kg,sum(tl.boxes) boxes from trip"
                + " t join batch b on b.id=t.batch_id join operation o on o.id=b.operation_id join"
                + " trip_line tl on tl.trip_id=t.id where t.status in"
                + " ('MOVING','ARRIVED','RECEIVING') group by o.id"),
        "low_stock",
        db.queryForList(
            "select warehouse_id,warehouse,sku_id,sku,name,sum(available) available,max(min_stock)"
                + " threshold from stock_view group by warehouse_id,warehouse,sku_id,sku,name"
                + " having sum(available)<max(min_stock) order by available limit 100"));
  }

  @PostMapping("/documents/{id}/attachments")
  @Transactional
  Object upload(@PathVariable long id, @RequestParam("file") MultipartFile f, HttpSession s)
      throws Exception {
    var u = user(s);
    role(u, "DISPATCH");
    db.execute("select pg_advisory_xact_lock(20261107)");
    need(
        "DRAFT".equals(e.row("document", id).get("status")),
        "Đính kèm trước khi xác nhận chứng từ");
    byte[] bytes = f.getBytes();
    need(bytes.length > 4 && bytes.length <= 10 * 1024 * 1024, "Tệp từ 5 byte đến 10 MB");
    String mime;
    if (bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F')
      mime = "application/pdf";
    else if (bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G')
      mime = "image/png";
    else if (bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8) mime = "image/jpeg";
    else throw new IllegalArgumentException("Chỉ đính kèm PDF, PNG, JPG");
    String name =
        Objects.toString(f.getOriginalFilename(), "attachment")
            .replaceAll("[^\\p{L}\\p{N}._ -]", "_");
    long aid =
        e.insert(
            "insert into attachment(document_id,name,mime,bytes,sha256,created_by)"
                + " values(?,?,?,?,?,?)",
            id,
            name,
            mime,
            bytes,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            u.get("id"));
    e.audit(u, "ATTACH", "document", id, Map.of("attachment", aid));
    return Map.of("id", aid);
  }

  @GetMapping("/attachments/{id}")
  ResponseEntity<byte[]> download(@PathVariable long id, HttpSession s) {
    user(s);
    var a = e.row("attachment", id);
    return ResponseEntity.ok()
        .header(
            "Content-Disposition",
            ContentDisposition.attachment()
                .filename(a.get("name").toString(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(MediaType.parseMediaType(a.get("mime").toString()))
        .body((byte[]) a.get("bytes"));
  }

  @GetMapping("/documents/{id}/pdf")
  ResponseEntity<byte[]> pdf(@PathVariable long id, HttpSession s) throws Exception {
    user(s);
    var doc = e.row("document", id);
    need("CONFIRMED".equals(doc.get("status")), "Xác nhận chứng từ trước khi xuất PDF");
    return ResponseEntity.ok()
        .header("Content-Disposition", "inline; filename=\"document-" + id + ".pdf\"")
        .contentType(MediaType.APPLICATION_PDF)
        .body(pdf.render(doc));
  }

  @GetMapping("/export/{name}")
  ResponseEntity<byte[]> export(@PathVariable String name, HttpSession s) throws Exception {
    var rows = data(name, user(s));
    StringWriter out = new StringWriter();
    out.write('\ufeff');
    if (!rows.isEmpty()) {
      var headers = rows.get(0).keySet().toArray(String[]::new);
      try (CSVPrinter p =
          new CSVPrinter(out, CSVFormat.DEFAULT.builder().setHeader(headers).build())) {
        for (var r : rows) {
          List<String> vals = new ArrayList<>();
          for (String h : headers) {
            String v = Objects.toString(r.get(h), "");
            if (v.matches("^[\\s]*[=+@-].*")) v = "'" + v;
            vals.add(v);
          }
          p.printRecord(vals);
        }
      }
    }
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=\"" + name + ".csv\"")
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .body(out.toString().getBytes(StandardCharsets.UTF_8));
  }

  @PostMapping("/import/{name}")
  @Transactional
  Object csv(@PathVariable String name, @RequestParam("file") MultipartFile f, HttpSession s)
      throws Exception {
    var u = user(s);
    need(
        Set.of("sku", "partner").contains(name),
        "Nhập CSV danh mục SKU/đối tác; không sửa tồn bằng CSV");
    if (name.equals("sku")) role(u, "ADMIN", "WAREHOUSE");
    else role(u, "ADMIN", "DISPATCH");
    need(f.getSize() <= 2 * 1024 * 1024, "CSV tối đa 2 MB");
    String csv = new String(f.getBytes(), StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "");
    int count = 0;
    try (CSVParser p =
        CSVParser.parse(
            csv,
            CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .build())) {
      var required =
          name.equals("sku")
              ? List.of(
                  "code",
                  "name",
                  "category",
                  "pack_kg",
                  "weight_tolerance",
                  "temp_min",
                  "temp_max",
                  "min_days",
                  "warning_days",
                  "min_stock")
              : List.of("code", "name", "kind", "country", "address", "email", "phone");
      need(p.getHeaderMap().keySet().containsAll(required), "Thiếu cột: " + required);
      for (CSVRecord r : p) {
        need(++count <= 2000, "Tối đa 2000 dòng mỗi lần nhập");
        Map<String, Object> b = new LinkedHashMap<>(r.toMap());
        b.remove("id");
        b.put("active", true);
        try {
          e.command(name + ".save", b, u);
        } catch (Exception ex) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "CSV dòng "
                  + (r.getRecordNumber() + 1)
                  + ": "
                  + (ex instanceof ResponseStatusException re
                      ? re.getReason()
                      : "mã trùng hoặc dữ liệu không hợp lệ"));
        }
      }
    }
    return Map.of("inserted", count);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> error(Exception ex) {
    if (ex instanceof ResponseStatusException re)
      return ResponseEntity.status(re.getStatusCode())
          .body(Map.of("error", Objects.toString(re.getReason(), "Yêu cầu không hợp lệ")));
    if (ex instanceof IllegalArgumentException)
      return ResponseEntity.badRequest()
          .body(Map.of("error", Objects.toString(ex.getMessage(), "Dữ liệu không hợp lệ")));
    org.slf4j.LoggerFactory.getLogger(Api.class).warn("Request failed", ex);
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "error",
                "Dữ liệu vi phạm ràng buộc hoặc mã bị trùng. Kiểm tra trường nhập và nhật ký máy"
                    + " chủ."));
  }
}

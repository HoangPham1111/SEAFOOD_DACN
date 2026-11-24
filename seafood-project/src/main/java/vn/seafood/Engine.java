package vn.seafood;

import static vn.seafood.Util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Engine {
  final JdbcTemplate db;
  final ObjectMapper json;
  final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

  public Engine(JdbcTemplate d, ObjectMapper j) {
    db = d;
    json = j;
  }

  public JdbcTemplate jdbc() {
    return db;
  }

  public BCryptPasswordEncoder encoder() {
    return encoder;
  }

  public ObjectMapper mapper() {
    return json;
  }

  public String json(Object o) {
    try {
      return json.writeValueAsString(o);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  public Map<String, Object> row(String table, Object id) {
    var r = db.queryForList("select * from " + table + " where id=?", id);
    need(r.size() == 1, "Không tìm thấy " + table + " #" + id);
    return r.get(0);
  }

  long insert(String sql, Object... v) {
    return db.queryForObject(sql + " returning id", Long.class, v);
  }

  void audit(Map<String, Object> u, String action, String entity, Object id, Object details) {
    db.update(
        "insert into audit(actor_id,action,entity,entity_id,details) values(?,?,?,?,?::jsonb)",
        u.get("id"),
        action,
        entity,
        id,
        json(details));
  }

  void editable(Map<String, Object> u, Map<String, Object> o) {
    need(same(u.get("id"), o.get("created_by")), "Chỉ người lập được sửa/gửi hồ sơ");
    need("DRAFT".equals(o.get("status")), "Chỉ sửa hồ sơ nháp");
  }

  void approve(Map<String, Object> u, Map<String, Object> o) {
    role(u, "ADMIN");
    need(!same(u.get("id"), o.get("created_by")), "Người tạo không được tự duyệt");
  }

  void capacity(Object id, BigDecimal add) {
    var c = db.queryForMap("select * from capacity_view where id=?", id);
    need(
        n(c.get("capacity"))
                .subtract(n(c.get("used")))
                .subtract(n(c.get("promised")))
                .compareTo(add)
            >= 0,
        "Kho không đủ sức chứa (đã tính cả hàng được giữ chỗ)");
  }

  void warehouseFor(Object id, Object sku) {
    var w = row("warehouse", id);
    var k = row("sku", sku);
    need(Boolean.TRUE.equals(w.get("active")), "Kho đã ngừng hoạt động");
    BigDecimal t = n(w.get("temperature"));
    need(
        t.compareTo(n(k.get("temp_min"))) >= 0 && t.compareTo(n(k.get("temp_max"))) <= 0,
        "Nhiệt độ kho không phù hợp SKU");
  }

  void lotUsable(Object lot) {
    need(
        db.queryForObject(
                "select count(*) from lot l join sku s on s.id=l.sku_id where l.id=? and s.active"
                    + " and l.expires>=current_date+s.min_days",
                Integer.class,
                lot)
            == 1,
        "Lô hết hạn/không đủ số ngày còn lại hoặc SKU ngừng kinh doanh");
  }

  void weight(Object sku, BigDecimal kg, int boxes) {
    var s = row("sku", sku);
    need(kg.signum() > 0 && boxes > 0, "Kg và số thùng phải lớn hơn 0");
    BigDecimal target = n(s.get("pack_kg")).multiply(BigDecimal.valueOf(boxes));
    BigDecimal diff = kg.subtract(target).abs().multiply(BigDecimal.valueOf(100));
    need(
        diff.compareTo(target.multiply(n(s.get("weight_tolerance")))) <= 0,
        "Khối lượng/thùng vượt dung sai SKU; kiểm tra quy cách hoặc chia lại dòng");
  }

  void stockMove(
      Object warehouse,
      Object lot,
      BigDecimal delta,
      int boxes,
      Object op,
      Object trip,
      String kind,
      Map<String, Object> u,
      String reason) {
    db.update(
        "insert into stock(warehouse_id,lot_id) values(?,?) on conflict do nothing",
        warehouse,
        lot);
    var st =
        db.queryForMap("select * from stock where warehouse_id=? and lot_id=?", warehouse, lot);
    BigDecimal before = n(st.get("kg"));
    db.update(
        "update stock set kg=kg+?,boxes=boxes+?,version=version+1 where id=?",
        delta,
        boxes,
        st.get("id"));
    db.update(
        "insert into"
            + " movement(warehouse_id,lot_id,operation_id,trip_id,kind,delta,delta_boxes,before_kg,after_kg,actor_id,reason)"
            + " values(?,?,?,?,?,?,?,?,?,?,?)",
        warehouse,
        lot,
        op,
        trip,
        kind,
        delta,
        boxes,
        before,
        before.add(delta),
        u.get("id"),
        reason);
  }

  @Transactional
  public Object command(String action, Map<String, Object> b, Map<String, Object> u) {
    // One transaction-scoped lock serializes domain writes across application instances.
    // Deliberate for a three-warehouse academic deployment; avoids overselling and lock-order
    // deadlocks.
    db.execute("select pg_advisory_xact_lock(20261107)");
    Object result =
        switch (action) {
          case "sku.save",
                  "sku.delete",
                  "warehouse.save",
                  "partner.save",
                  "lot.save",
                  "resource.save",
                  "user.save",
                  "profile.password",
                  "settings.save" ->
              catalog(action, b, u);
          case "demand.create", "demand.decide" -> demand(action, b, u);
          case "operation.create",
                  "operation.update",
                  "operation.submit",
                  "operation.approve",
                  "operation.reject",
                  "operation.cancel" ->
              operation(action, b, u);
          case "batch.create", "batch.clearance" -> batch(action, b, u);
          case "trip.create",
                  "trip.depart",
                  "trip.arrive",
                  "trip.receiving",
                  "trip.receive",
                  "trip.deliver",
                  "trip.return",
                  "trip.cancel",
                  "trip.reposition" ->
              trip(action, b, u);
          case "position.create" -> position(b, u);
          case "discrepancy.resolve",
                  "count.create",
                  "count.decide",
                  "return.create",
                  "return.decide",
                  "return.receive",
                  "return.resolve" ->
              inventory(action, b, u);
          case "document.save", "document.confirm" -> document(action, b, u);
          default -> throw new IllegalArgumentException("Thao tác không hỗ trợ");
        };
    audit(
        u,
        action,
        action.split("\\.")[0],
        optionalId(b, "id"),
        Map.of("result", result == null ? "OK" : result));
    return result == null ? Map.of("message", "Đã xử lý") : result;
  }

  Object catalog(String action, Map<String, Object> b, Map<String, Object> u) {
    Long id = optionalId(b, "id");
    switch (action) {
      case "sku.save":
        {
          role(u, "ADMIN", "WAREHOUSE");
          required(b, "code");
          required(b, "name");
          required(b, "category");
          BigDecimal pack = dec(b, "pack_kg"),
              tol = dec(b, "weight_tolerance"),
              min = dec(b, "temp_min"),
              max = dec(b, "temp_max");
          need(
              pack.signum() > 0
                  && tol.signum() >= 0
                  && tol.compareTo(new BigDecimal(100)) <= 0
                  && min.compareTo(max) <= 0,
              "Thông số SKU không hợp lệ");
          int days = integer(b, "min_days"), warn = integer(b, "warning_days");
          BigDecimal low = dec(b, "min_stock");
          need(days >= 0 && warn >= 0 && low.signum() >= 0, "Ngưỡng không âm");
          if (id == null)
            id =
                insert(
                    "insert into"
                        + " sku(code,name,name_en,category,pack_kg,weight_tolerance,temp_min,temp_max,min_days,warning_days,min_stock)"
                        + " values(?,?,?,?,?,?,?,?,?,?,?)",
                    str(b, "code"),
                    str(b, "name"),
                    str(b, "name_en"),
                    str(b, "category"),
                    pack,
                    tol,
                    min,
                    max,
                    days,
                    warn,
                    low);
          else
            db.update(
                "update sku set"
                    + " name=?,name_en=?,category=?,pack_kg=?,weight_tolerance=?,temp_min=?,temp_max=?,min_days=?,warning_days=?,min_stock=?,active=?"
                    + " where id=?",
                str(b, "name"),
                str(b, "name_en"),
                str(b, "category"),
                pack,
                tol,
                min,
                max,
                days,
                warn,
                low,
                yes(b, "active"),
                id);
          break;
        }
      case "sku.delete":
        role(u, "ADMIN", "WAREHOUSE");
        need(
            db.queryForObject("select count(*) from lot where sku_id=?", Integer.class, id) == 0,
            "SKU đã phát sinh dữ liệu: hãy ngừng kinh doanh");
        db.update("delete from sku where id=?", id);
        break;
      case "warehouse.save":
        {
          role(u, "ADMIN", "DISPATCH");
          double lat = Double.parseDouble(required(b, "lat")),
              lon = Double.parseDouble(required(b, "lon"));
          need(lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180, "Tọa độ không hợp lệ");
          BigDecimal cap = dec(b, "capacity"), temp = dec(b, "temperature");
          if (id != null) {
            var c = db.queryForMap("select * from capacity_view where id=?", id);
            need(
                cap.compareTo(n(c.get("used")).add(n(c.get("promised")))) >= 0,
                "Sức chứa nhỏ hơn hàng hiện có và giữ chỗ");
            need(
                db.queryForObject(
                        "select count(*) from stock s join lot l on l.id=s.lot_id join sku k on"
                            + " k.id=l.sku_id where s.warehouse_id=? and s.kg>0 and (?<k.temp_min"
                            + " or ?>k.temp_max)",
                        Integer.class,
                        id,
                        temp,
                        temp)
                    == 0,
                "Nhiệt độ mới không phù hợp hàng đang lưu");
            need(
                yes(b, "active") || n(c.get("used")).add(n(c.get("promised"))).signum() == 0,
                "Kho còn hàng/cam kết nhận");
            db.update(
                "update warehouse set"
                    + " name=?,address=?,capacity=?,temperature=?,active=?,location=ST_SetSRID(ST_MakePoint(?,?),4326)"
                    + " where id=?",
                required(b, "name"),
                required(b, "address"),
                cap,
                temp,
                yes(b, "active"),
                lon,
                lat,
                id);
          } else
            id =
                insert(
                    "insert into warehouse(code,name,address,capacity,temperature,location)"
                        + " values(?,?,?,?,?,ST_SetSRID(ST_MakePoint(?,?),4326))",
                    required(b, "code"),
                    required(b, "name"),
                    required(b, "address"),
                    cap,
                    temp,
                    lon,
                    lat);
          break;
        }
      case "partner.save":
        role(u, "ADMIN", "DISPATCH");
        if (id == null)
          id =
              insert(
                  "insert into partner(code,name,kind,country,address,email,phone)"
                      + " values(?,?,?,?,?,?,?)",
                  required(b, "code"),
                  required(b, "name"),
                  required(b, "kind"),
                  required(b, "country").toUpperCase(),
                  required(b, "address"),
                  str(b, "email"),
                  str(b, "phone"));
        else
          db.update(
              "update partner set name=?,country=?,address=?,email=?,phone=?,active=? where id=?",
              required(b, "name"),
              required(b, "country").toUpperCase(),
              required(b, "address"),
              str(b, "email"),
              str(b, "phone"),
              yes(b, "active"),
              id);
        break;
      case "lot.save":
        {
          role(u, "DISPATCH", "WAREHOUSE");
          need(id == null, "Lô đã tạo không được đổi danh tính; tạo lô mới nếu nhập sai");
          var p = row("partner", id(b, "supplier_id"));
          need("SUPPLIER".equals(p.get("kind")), "Phải chọn nhà cung cấp");
          date(required(b, "manufactured"));
          date(required(b, "expires"));
          id =
              insert(
                  "insert into lot(code,sku_id,supplier_id,origin,manufactured,expires)"
                      + " values(?,?,?,?,?::date,?::date)",
                  required(b, "code"),
                  id(b, "sku_id"),
                  id(b, "supplier_id"),
                  required(b, "origin"),
                  str(b, "manufactured"),
                  str(b, "expires"));
          break;
        }
      case "resource.save":
        {
          role(u, "ADMIN", "DISPATCH");
          String kind = required(b, "kind"), code = required(b, "code").toUpperCase();
          boolean owned = yes(b, "owned");
          if (owned) role(u, "ADMIN");
          if (kind.equals("CONTAINER")) {
            need(containerCode(code), "Mã container phải đúng cấu trúc và số kiểm tra ISO 6346");
            need(str(b, "type_code").matches("[24][25]R1"), "Container lạnh: 22R1/25R1/42R1/45R1");
          }
          if (Set.of("TRACTOR", "TRAILER").contains(kind))
            need(
                code.matches("[0-9]{2}[A-Z]{1,2}-[0-9]{5}"),
                "Biển số dạng 51H-12345 hoặc 51R-12345");
          if (!owned) {
            var p = row("partner", id(b, "partner_id"));
            need("CARRIER".equals(p.get("kind")), "Chọn đơn vị cho thuê");
          }
          if (kind.equals("DRIVER")) date(required(b, "license_expiry"));
          if (id != null) {
            var r = row("resource", id);
            if (Boolean.TRUE.equals(r.get("owned"))) role(u, "ADMIN");
            need(!"BUSY".equals(r.get("status")), "Nguồn lực đang có chuyến");
            need(
                same(r.get("code"), code) && same(r.get("kind"), kind),
                "Không đổi mã/loại nguồn lực đã tạo");
            need(
                same(r.get("place"), b.get("place")),
                "Đổi vị trí bằng chuyến điều động, không sửa trực tiếp");
            need(
                Set.of("READY", "MAINTENANCE").contains(str(b, "status")),
                "Trạng thái không hợp lệ");
            db.update(
                "update resource set"
                    + " name=?,payload=?,temp_min=?,temp_max=?,status=?,license_expiry=?::date,phone=?"
                    + " where id=?",
                required(b, "name"),
                dec(b, "payload"),
                dec(b, "temp_min"),
                dec(b, "temp_max"),
                str(b, "status"),
                str(b, "license_expiry").isBlank() ? null : str(b, "license_expiry"),
                str(b, "phone"),
                id);
          } else
            id =
                insert(
                    "insert into"
                        + " resource(kind,code,name,owned,partner_id,payload,type_code,temp_min,temp_max,place,license_expiry,phone)"
                        + " values(?,?,?,?,?,?,?,?,?,?,?::date,?)",
                    kind,
                    code,
                    required(b, "name"),
                    owned,
                    optionalId(b, "partner_id"),
                    dec(b, "payload"),
                    str(b, "type_code"),
                    dec(b, "temp_min"),
                    dec(b, "temp_max"),
                    required(b, "place"),
                    str(b, "license_expiry").isBlank() ? null : str(b, "license_expiry"),
                    str(b, "phone"));
          break;
        }
      case "user.save":
        role(u, "ADMIN");
        need(id != null && !same(id, u.get("id")), "Không tự thay đổi quyền/trạng thái của mình");
        db.update(
            "update app_user set role=?,warehouse_id=?,status=? where id=?",
            required(b, "role"),
            optionalId(b, "warehouse_id"),
            required(b, "status"),
            id);
        break;
      case "profile.password":
        {
          var a = row("app_user", u.get("id"));
          need(
              encoder.matches(required(b, "old_password"), a.get("password").toString()),
              "Mật khẩu cũ không đúng");
          need(required(b, "new_password").length() >= 8, "Mật khẩu mới tối thiểu 8 ký tự");
          db.update(
              "update app_user set password=? where id=?",
              encoder.encode(str(b, "new_password")),
              u.get("id"));
          break;
        }
      case "settings.save":
        role(u, "ADMIN");
        for (String k :
            List.of("company_name", "company_name_en", "company_address", "company_address_en"))
          db.update("update setting set value=? where key=?", required(b, k), k);
        break;
    }
    return Map.of("id", id == null ? 0 : id);
  }

  Object demand(String action, Map<String, Object> b, Map<String, Object> u) {
    if (action.equals("demand.create")) {
      own(u, id(b, "warehouse_id"));
      return Map.of(
          "id",
          insert(
              "insert into demand(warehouse_id,sku_id,kind,kg,reason,created_by)"
                  + " values(?,?,?,?,?,?)",
              id(b, "warehouse_id"),
              id(b, "sku_id"),
              required(b, "kind"),
              dec(b, "kg"),
              required(b, "reason"),
              u.get("id")));
    }
    var d = row("demand", id(b, "id"));
    approve(u, d);
    need("PENDING".equals(d.get("status")), "Nhu cầu đã xử lý");
    db.update(
        "update demand set status=?,approved_by=?,decision=? where id=?",
        yes(b, "approve") ? "APPROVED" : "REJECTED",
        u.get("id"),
        required(b, "reason"),
        d.get("id"));
    return null;
  }

  Object operation(String action, Map<String, Object> b, Map<String, Object> u) {
    Long id = optionalId(b, "id");
    Map<String, Object> o = id == null ? null : row("operation", id);
    if (action.equals("operation.create") || action.equals("operation.update")) {
      role(u, "WAREHOUSE", "DISPATCH");
      String kind = required(b, "kind");
      Long src = optionalId(b, "source_id"),
          dst = optionalId(b, "destination_id"),
          partner = optionalId(b, "partner_id"),
          demand = optionalId(b, "demand_id");
      if (kind.equals("IMPORT")) {
        role(u, "DISPATCH");
        need(dst != null && src == null, "Nhập khẩu cần một kho đích");
      } else if (kind.equals("TRANSFER")) {
        need(
            src != null && dst != null && !src.equals(dst), "Kho nguồn và kho đích phải khác nhau");
        if ("WAREHOUSE".equals(u.get("role")))
          need(
              same(u.get("warehouse_id"), src) || same(u.get("warehouse_id"), dst),
              "Yêu cầu phải liên quan kho phụ trách");
      } else {
        need(
            Set.of("EXPORT", "DOMESTIC").contains(kind) && src != null && dst == null,
            "Loại yêu cầu không hợp lệ");
        if ("WAREHOUSE".equals(u.get("role"))) own(u, src);
      }
      if (!kind.equals("TRANSFER")) {
        var p = row("partner", partner);
        need(Boolean.TRUE.equals(p.get("active")), "Đối tác ngừng hoạt động");
        need(
            (kind.equals("IMPORT") ? "SUPPLIER" : "CUSTOMER").equals(p.get("kind")),
            "Loại đối tác không phù hợp");
        need(
            kind.equals("DOMESTIC") == "VN".equals(p.get("country")),
            "Nhập/xuất khẩu cần đối tác nước ngoài; xuất nội địa cần đối tác VN");
      }
      if (demand != null)
        need("APPROVED".equals(row("demand", demand).get("status")), "Nhu cầu phải được duyệt");
      date(required(b, "planned_date"));
      String
          origin =
              src == null
                  ? required(b, "origin_place")
                  : row("warehouse", src).get("code").toString(),
          destination =
              dst == null
                  ? required(b, "destination_place")
                  : row("warehouse", dst).get("code").toString();
      if (id == null)
        id =
            insert(
                "insert into"
                    + " operation(code,kind,source_id,destination_id,partner_id,demand_id,origin_place,destination_place,planned_date,reason,created_by)"
                    + " values(?,?,?,?,?,?,?,?,?::date,?,?)",
                code(kind),
                kind,
                src,
                dst,
                partner,
                demand,
                origin,
                destination,
                str(b, "planned_date"),
                required(b, "reason"),
                u.get("id"));
      else {
        editable(u, o);
        need(
            db.queryForObject(
                    "select count(*) from document where operation_id=? and status='CONFIRMED'",
                    Integer.class,
                    id)
                == 0,
            "Đã xác nhận chứng từ: tạo yêu cầu mới để tránh thay đổi dữ liệu đã chốt");
        db.update(
            "update operation set"
                + " kind=?,source_id=?,destination_id=?,partner_id=?,demand_id=?,origin_place=?,destination_place=?,planned_date=?::date,reason=?"
                + " where id=?",
            kind,
            src,
            dst,
            partner,
            demand,
            origin,
            destination,
            str(b, "planned_date"),
            required(b, "reason"),
            id);
        db.update("delete from operation_line where operation_id=?", id);
      }
      var lines = list(b, "lines");
      need(!lines.isEmpty() && lines.size() <= 100, "Cần 1–100 dòng hàng");
      BigDecimal total = BigDecimal.ZERO;
      for (var l : lines) {
        long sku = id(l, "sku_id");
        Long lot = optionalId(l, "lot_id");
        BigDecimal kg = dec(l, "kg");
        int boxes = integer(l, "boxes");
        weight(sku, kg, boxes);
        need(Boolean.TRUE.equals(row("sku", sku).get("active")), "SKU ngừng kinh doanh");
        if (lot != null) need(same(row("lot", lot).get("sku_id"), sku), "Lô không thuộc SKU");
        if (kind.equals("IMPORT")) {
          need(lot != null, "Nhập khẩu phải chỉ rõ lô sản phẩm");
          need(
              same(row("lot", lot).get("supplier_id"), partner),
              "Nhà cung cấp lô không khớp hồ sơ nhập");
          lotUsable(lot);
        }
        if (dst != null) warehouseFor(dst, sku);
        insert(
            "insert into operation_line(operation_id,sku_id,lot_id,kg,boxes,price)"
                + " values(?,?,?,?,?,?)",
            id,
            sku,
            lot,
            kg,
            boxes,
            l.containsKey("price") ? dec(l, "price") : BigDecimal.ZERO);
        total = total.add(kg);
      }
      if (kind.equals("IMPORT")) capacity(dst, total);
      return Map.of("id", id);
    }
    need(o != null, "Thiếu yêu cầu");
    String state = o.get("status").toString();
    if (action.equals("operation.submit")) {
      editable(u, o);
      need(hasDoc(id, "REQUEST"), "Cần phiếu yêu cầu đã xác nhận");
      db.update("update operation set status='PENDING' where id=?", id);
    }
    if (action.equals("operation.approve")) {
      approve(u, o);
      need(state.equals("PENDING"), "Chỉ duyệt yêu cầu chờ duyệt");
      need(hasDoc(id, "REQUEST"), "Thiếu phiếu yêu cầu");
      String kind = o.get("kind").toString();
      if (Set.of("IMPORT", "EXPORT").contains(kind))
        for (String doc : List.of("CONTRACT", "INVOICE", "PACKING"))
          need(hasDoc(id, doc), "Thiếu chứng từ đã xác nhận: " + doc);
      var lines =
          db.queryForList("select * from operation_line where operation_id=? order by id", id);
      BigDecimal total =
          lines.stream().map(x -> n(x.get("kg"))).reduce(BigDecimal.ZERO, BigDecimal::add);
      if (o.get("destination_id") != null) capacity(o.get("destination_id"), total);
      for (var l : lines) {
        if (o.get("destination_id") != null) warehouseFor(o.get("destination_id"), l.get("sku_id"));
        if (Set.of("IMPORT", "EXPORT").contains(kind))
          need(
              !str(row("sku", l.get("sku_id")), "name_en").isBlank(),
              "SKU cần tên tiếng Anh để lập hồ sơ ngoại thương");
        if (kind.equals("IMPORT")) {
          lotUsable(l.get("lot_id"));
          insert(
              "insert into allocation(line_id,lot_id,kg,boxes) values(?,?,?,?)",
              l.get("id"),
              l.get("lot_id"),
              l.get("kg"),
              l.get("boxes"));
          continue;
        }
        // FEFO first, then lot creation time, stable id; reserves kg and boxes independently.
        var stocks =
            db.queryForList(
                "select s.*,l.expires from stock s join lot l on l.id=s.lot_id join sku k on"
                    + " k.id=l.sku_id where s.warehouse_id=? and l.sku_id=? and k.active and"
                    + " l.expires>=current_date+k.min_days and s.kg-s.blocked-s.reserved>0 and"
                    + " s.boxes-s.blocked_boxes-s.reserved_boxes>0 order by"
                    + " l.expires,l.created_at,l.id",
                o.get("source_id"),
                l.get("sku_id"));
        BigDecimal remain = n(l.get("kg"));
        int remainB = ((Number) l.get("boxes")).intValue();
        for (var st : stocks) {
          if (remain.signum() == 0) break;
          if (l.get("lot_id") != null && !same(l.get("lot_id"), st.get("lot_id"))) continue;
          BigDecimal av =
              n(st.get("kg")).subtract(n(st.get("blocked"))).subtract(n(st.get("reserved")));
          int ab =
              ((Number) st.get("boxes")).intValue()
                  - ((Number) st.get("blocked_boxes")).intValue()
                  - ((Number) st.get("reserved_boxes")).intValue();
          BigDecimal ratio = remain.divide(BigDecimal.valueOf(remainB), 9, RoundingMode.HALF_UP);
          int takeB =
              Math.min(remainB, Math.min(ab, av.divide(ratio, 0, RoundingMode.DOWN).intValue()));
          if (takeB <= 0) continue;
          BigDecimal take =
              takeB == remainB
                  ? remain
                  : ratio.multiply(BigDecimal.valueOf(takeB)).setScale(3, RoundingMode.DOWN);
          db.update(
              "update stock set"
                  + " reserved=reserved+?,reserved_boxes=reserved_boxes+?,version=version+1 where"
                  + " id=?",
              take,
              takeB,
              st.get("id"));
          insert(
              "insert into allocation(line_id,stock_id,lot_id,kg,boxes) values(?,?,?,?,?)",
              l.get("id"),
              st.get("id"),
              st.get("lot_id"),
              take,
              takeB);
          remain = remain.subtract(take);
          remainB -= takeB;
        }
        need(
            remain.signum() == 0 && remainB == 0,
            "Không đủ tồn khả dụng cả kg và thùng; hoặc lô chọn không đủ điều kiện");
      }
      db.update(
          "update operation set status='APPROVED',approved_by=?,decision=? where id=?",
          u.get("id"),
          str(b, "reason"),
          id);
    }
    if (action.equals("operation.reject")) {
      approve(u, o);
      need(state.equals("PENDING"), "Chỉ từ chối yêu cầu đang chờ");
      db.update(
          "update operation set status='REJECTED',approved_by=?,decision=? where id=?",
          u.get("id"),
          required(b, "reason"),
          id);
    }
    if (action.equals("operation.cancel")) {
      need(
          "ADMIN".equals(u.get("role")) || same(u.get("id"), o.get("created_by")),
          "Không có quyền hủy");
      need(
          Set.of("DRAFT", "PENDING", "APPROVED").contains(state),
          "Đã vận chuyển không được hủy toàn bộ");
      need(
          db.queryForObject(
                  "select count(*) from trip t join batch b on b.id=t.batch_id where"
                      + " b.operation_id=? and t.status<>'CANCELLED'",
                  Integer.class,
                  id)
              == 0,
          "Phải hủy các chuyến chưa chạy trước");
      for (var a :
          db.queryForList(
              "select a.* from allocation a join operation_line l on l.id=a.line_id where"
                  + " l.operation_id=?",
              id))
        if (a.get("stock_id") != null)
          db.update(
              "update stock set"
                  + " reserved=reserved-?,reserved_boxes=reserved_boxes-?,version=version+1 where"
                  + " id=?",
              a.get("kg"),
              a.get("boxes"),
              a.get("stock_id"));
      db.update(
          "update operation set status='CANCELLED',decision=? where id=?",
          required(b, "reason"),
          id);
    }
    return Map.of("id", id);
  }

  boolean hasDoc(Object op, String kind) {
    return db.queryForObject(
            "select count(*) from document where operation_id=? and kind=? and status='CONFIRMED'",
            Integer.class,
            op,
            kind)
        > 0;
  }

  Object batch(String action, Map<String, Object> b, Map<String, Object> u) {
    role(u, "DISPATCH");
    if (action.equals("batch.create")) {
      var o = row("operation", id(b, "operation_id"));
      need(
          Set.of("APPROVED", "IN_PROGRESS").contains(o.get("status")),
          "Yêu cầu chưa duyệt/đã kết thúc");
      date(required(b, "planned_date"));
      long id =
          insert(
              "insert into batch(operation_id,code,planned_date,created_by) values(?,?,?::date,?)",
              o.get("id"),
              code("BATCH"),
              str(b, "planned_date"),
              u.get("id"));
      return Map.of("id", id);
    }
    var ba = row("batch", id(b, "id"));
    var o = row("operation", ba.get("operation_id"));
    need(
        Set.of("IMPORT", "EXPORT").contains(o.get("kind")),
        "Chỉ hồ sơ ngoại thương mới có thủ tục hải quan");
    need(
        db.queryForObject(
                "select count(*) from trip t join receipt r on r.trip_id=t.id where t.batch_id=?",
                Integer.class,
                ba.get("id"))
            == 0,
        "Đợt đã nhận/giao hàng: không sửa kết quả thủ tục");
    String customs = required(b, "customs"),
        quality = required(b, "quality"),
        reason = required(b, "reason");
    need(
        Set.of("PENDING", "PROCESSING", "PASSED", "REJECTED").contains(customs)
            && Set.of("PENDING", "PASSED", "FAILED", "QUARANTINE").contains(quality),
        "Kết quả không hợp lệ");
    for (String k : List.of("CUSTOMS", "QUARANTINE"))
      need(
          db.queryForObject(
                  "select count(*) from document d join attachment a on a.document_id=d.id where"
                      + " d.batch_id=? and d.kind=? and d.status='CONFIRMED'",
                  Integer.class,
                  ba.get("id"),
                  k)
              > 0,
          "Phải có hồ sơ đính kèm đã xác nhận: " + k);
    String disposition = str(b, "disposition");
    if (customs.equals("REJECTED") || Set.of("FAILED", "QUARANTINE").contains(quality))
      need(
          Set.of("REEXPORT", "DESTROY", "RETURN_SUPPLIER", "ISOLATE").contains(disposition),
          "Chọn phương án xử lý bên ngoài");
    db.update(
        "update batch set customs=?,quality=?,reason=?,disposition=? where id=?",
        customs,
        quality,
        reason,
        disposition,
        ba.get("id"));
    audit(u, "CLEARANCE_EVIDENCE", "batch", ba.get("id"), Map.of("before", ba, "after", b));
    return null;
  }

  List<Map<String, Object>> tripLines(Object id) {
    return db.queryForList(
        "select tl.*,a.lot_id,a.line_id,a.stock_id,l.sku_id,l.operation_id from trip_line tl join"
            + " allocation a on a.id=tl.allocation_id join operation_line l on l.id=a.line_id where"
            + " tl.trip_id=? order by tl.id",
        id);
  }

  Object trip(String action, Map<String, Object> b, Map<String, Object> u) {
    if (action.equals("trip.create") || action.equals("trip.reposition")) {
      role(u, "DISPATCH");
      boolean reposition = action.equals("trip.reposition");
      Map<String, Object> ba = reposition ? null : row("batch", id(b, "batch_id")),
          o = reposition ? null : row("operation", ba.get("operation_id"));
      if (!reposition)
        need(
            Set.of("APPROVED", "IN_PROGRESS").contains(o.get("status")),
            "Yêu cầu không còn cho phân chuyến");
      String origin = reposition ? required(b, "origin_place") : o.get("origin_place").toString(),
          dst =
              reposition ? required(b, "destination_place") : o.get("destination_place").toString();
      need(!origin.equals(dst), "Điểm đi và đến phải khác");
      BigDecimal temperature = dec(b, "temperature");
      for (String k : List.of("container", "tractor", "trailer", "driver")) {
        var r = row("resource", id(b, k + "_id"));
        need(
            k.toUpperCase().equals(r.get("kind")) && "READY".equals(r.get("status")),
            "Nguồn lực sai loại hoặc đang sử dụng: " + k);
        need(
            origin.equals(r.get("place")),
            "Nguồn lực chưa ở điểm xuất phát: " + r.get("code") + "; cần chuyến điều động trước");
        if (k.equals("driver"))
          need(
              r.get("license_expiry") != null
                  && !java.sql.Date.valueOf(LocalDate.now())
                      .after((java.sql.Date) r.get("license_expiry")),
              "Giấy phép tài xế đã hết hạn");
        if (k.equals("container"))
          need(
              temperature.compareTo(n(r.get("temp_min"))) >= 0
                  && temperature.compareTo(n(r.get("temp_max"))) <= 0,
              "Container không đáp ứng nhiệt độ");
      }
      String departure = required(b, "departure_at");
      try {
        OffsetDateTime.parse(departure);
      } catch (Exception e) {
        throw new IllegalArgumentException("Thời gian khởi hành cần múi giờ");
      }
      long tid =
          insert(
              "insert into"
                  + " trip(code,batch_id,kind,container_id,tractor_id,trailer_id,driver_id,origin_place,destination_place,departure_at,temperature,created_by)"
                  + " values(?,?,?,?,?,?,?,?,?,?::timestamptz,?,?)",
              code("TRIP"),
              reposition ? null : ba.get("id"),
              reposition ? "REPOSITION" : "CARGO",
              id(b, "container_id"),
              id(b, "tractor_id"),
              id(b, "trailer_id"),
              id(b, "driver_id"),
              origin,
              dst,
              departure,
              temperature,
              u.get("id"));
      if (!reposition) {
        var lines = list(b, "lines");
        need(!lines.isEmpty(), "Cần dòng hàng cho chuyến");
        BigDecimal total = BigDecimal.ZERO;
        for (var l : lines) {
          var a = row("allocation", id(l, "allocation_id"));
          var ol = row("operation_line", a.get("line_id"));
          need(same(ol.get("operation_id"), o.get("id")), "Dòng phân bổ không thuộc yêu cầu");
          BigDecimal kg = dec(l, "kg");
          int boxes = integer(l, "boxes");
          weight(ol.get("sku_id"), kg, boxes);
          var sk = row("sku", ol.get("sku_id"));
          need(
              temperature.compareTo(n(sk.get("temp_min"))) >= 0
                  && temperature.compareTo(n(sk.get("temp_max"))) <= 0,
              "Nhiệt độ chuyến không phù hợp hàng");
          var used =
              db.queryForMap(
                  "select coalesce(sum(tl.kg),0) kg,coalesce(sum(tl.boxes),0) boxes from trip_line"
                      + " tl join trip t on t.id=tl.trip_id where tl.allocation_id=? and"
                      + " t.status<>'CANCELLED'",
                  a.get("id"));
          need(
              n(used.get("kg")).add(kg).compareTo(n(a.get("kg"))) <= 0
                  && n(used.get("boxes")).intValue() + boxes <= n(a.get("boxes")).intValue(),
              "Phân công vượt lượng đã duyệt");
          insert(
              "insert into trip_line(trip_id,allocation_id,kg,boxes) values(?,?,?,?)",
              tid,
              a.get("id"),
              kg,
              boxes);
          total = total.add(kg);
        }
        for (String k : List.of("container", "tractor", "trailer"))
          need(
              total.compareTo(n(row("resource", id(b, k + "_id")).get("payload"))) <= 0,
              "Vượt tải " + k);
      }
      for (String k : List.of("container", "tractor", "trailer", "driver"))
        db.update("update resource set status='BUSY' where id=?", id(b, k + "_id"));
      return Map.of("id", tid);
    }
    long id = id(b, "id");
    var t = row("trip", id);
    boolean reposition = t.get("kind").equals("REPOSITION");
    var ba = reposition ? null : row("batch", t.get("batch_id"));
    var o = reposition ? null : row("operation", ba.get("operation_id"));
    String state = t.get("status").toString();
    switch (action) {
      case "trip.cancel":
        role(u, "DISPATCH");
        need(state.equals("ASSIGNED"), "Chỉ hủy chuyến chưa chạy");
        db.update("update trip set status='CANCELLED' where id=?", id);
        for (String k : List.of("container", "tractor", "trailer", "driver"))
          db.update("update resource set status='READY' where id=?", t.get(k + "_id"));
        break;
      case "trip.depart":
        {
          need(state.equals("ASSIGNED"), "Chuyến đã khởi hành");
          if (reposition) role(u, "DISPATCH");
          else {
            String kind = o.get("kind").toString();
            if (kind.equals("IMPORT")) {
              role(u, "DISPATCH");
              need(
                  ba.get("customs").equals("PASSED") && ba.get("quality").equals("PASSED"),
                  "Nhập khẩu chưa thông quan/kiểm dịch đạt");
            } else own(u, o.get("source_id"));
            if (kind.equals("EXPORT"))
              need(
                  ba.get("quality").equals("PASSED") && !ba.get("customs").equals("REJECTED"),
                  "Chất lượng xuất khẩu chưa đạt hoặc hải quan từ chối");
            need(
                db.queryForObject(
                        "select count(*) from document where trip_id=? and kind='TRIP_ORDER' and"
                            + " status='CONFIRMED'",
                        Integer.class,
                        id)
                    > 0,
                "Cần lệnh vận chuyển đã xác nhận cho chuyến");
            for (var l : tripLines(id)) {
              lotUsable(l.get("lot_id"));
              BigDecimal kg = n(l.get("kg"));
              int boxes = n(l.get("boxes")).intValue();
              if (o.get("source_id") != null) {
                var st = row("stock", l.get("stock_id"));
                need(
                    n(st.get("kg")).subtract(n(st.get("blocked"))).compareTo(kg) >= 0
                        && n(st.get("reserved")).compareTo(kg) >= 0
                        && n(st.get("reserved_boxes")).intValue() >= boxes,
                    "Hàng không còn đủ điều kiện xuất");
                db.update(
                    "update stock set reserved=reserved-?,reserved_boxes=reserved_boxes-? where"
                        + " id=?",
                    kg,
                    boxes,
                    st.get("id"));
                stockMove(
                    o.get("source_id"),
                    l.get("lot_id"),
                    kg.negate(),
                    -boxes,
                    o.get("id"),
                    id,
                    "OUT",
                    u,
                    "Xuất theo chuyến " + t.get("code"));
              }
              db.update(
                  "update allocation set shipped=shipped+?,shipped_boxes=shipped_boxes+? where"
                      + " id=?",
                  kg,
                  boxes,
                  l.get("allocation_id"));
            }
            db.update("update operation set status='IN_PROGRESS' where id=?", o.get("id"));
            if (o.get("source_id") != null) systemDoc(o, t, "WAREHOUSE_OUT", u);
          }
          db.update("update trip set status='MOVING' where id=?", id);
          autoPosition(t, "origin_place", u);
          break;
        }
      case "trip.arrive":
        role(u, "DISPATCH");
        need(state.equals("MOVING"), "Chuyến chưa đang vận chuyển");
        db.update("update trip set status=? where id=?", reposition ? "CLOSED" : "ARRIVED", id);
        if (!reposition) autoPosition(t, "destination_place", u);
        for (String k : List.of("container", "tractor", "trailer", "driver"))
          db.update(
              "update resource set place=?,status=? where id=?",
              t.get("destination_place"),
              reposition ? "READY" : "BUSY",
              t.get(k + "_id"));
        break;
      case "trip.receiving":
        need(!reposition && o.get("destination_id") != null, "Chuyến không nhận vào kho");
        own(u, o.get("destination_id"));
        need(state.equals("ARRIVED"), "Chuyến chưa đến");
        db.update("update trip set status='RECEIVING' where id=?", id);
        break;
      case "trip.receive":
      case "trip.deliver":
        {
          need(
              !reposition && Set.of("ARRIVED", "RECEIVING").contains(state),
              "Chuyến chưa đến hoặc đã nhận");
          boolean inbound = o.get("destination_id") != null;
          need(inbound == action.equals("trip.receive"), "Thao tác không phù hợp chuyến");
          if (inbound) own(u, o.get("destination_id"));
          else role(u, "DISPATCH");
          if (Set.of("IMPORT", "EXPORT").contains(o.get("kind")))
            need(
                ba.get("customs").equals("PASSED") && ba.get("quality").equals("PASSED"),
                "Phải có thông quan và chất lượng đạt");
          if (Set.of("IMPORT", "EXPORT").contains(o.get("kind")))
            for (String dk : List.of("BILL_OF_LADING", "ORIGIN", "FOOD_SAFETY"))
              need(
                  db.queryForObject(
                          "select count(*) from document d join attachment a on a.document_id=d.id"
                              + " where d.batch_id=? and d.kind=? and d.status='CONFIRMED'",
                          Integer.class,
                          ba.get("id"),
                          dk)
                      > 0,
                  "Thiếu hồ sơ đính kèm đã xác nhận: " + dk);
          var inputs = list(b, "lines");
          var lines = tripLines(id);
          need(inputs.size() == lines.size(), "Phải đối chiếu tất cả dòng hàng");
          Set<Long> seen = new HashSet<>();
          BigDecimal temp = dec(b, "temperature");
          String note = required(b, "reason");
          long rid =
              insert(
                  "insert into receipt(trip_id,warehouse_id,created_by,note,temperature)"
                      + " values(?,?,?,?,?)",
                  id,
                  o.get("destination_id"),
                  u.get("id"),
                  note,
                  temp);
          for (var l : lines) {
            var x =
                inputs.stream()
                    .filter(z -> same(z.get("trip_line_id"), l.get("id")))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Thiếu dòng thực nhận"));
            need(seen.add(id(x, "trip_line_id")), "Trùng dòng hàng");
            BigDecimal actual = dec(x, "actual");
            int boxes = integer(x, "boxes");
            BigDecimal block = dec(x, "blocked");
            int blockB = integer(x, "blocked_boxes");
            need(
                actual.signum() >= 0
                    && actual.compareTo(n(l.get("kg"))) <= 0
                    && boxes >= 0
                    && boxes <= n(l.get("boxes")).intValue(),
                "Thực nhận không âm, không vượt lượng trên phiếu; hàng dư phải bổ sung hồ sơ"
                    + " trước");
            need(
                block.signum() >= 0
                    && block.compareTo(actual) <= 0
                    && blockB >= 0
                    && blockB <= boxes,
                "Lượng khóa không hợp lệ");
            need(
                (actual.signum() == 0) == (boxes == 0),
                "Kg và thùng phải cùng bằng 0 hoặc cùng dương");
            var sk = row("sku", l.get("sku_id"));
            boolean badTemp =
                temp.compareTo(n(sk.get("temp_min"))) < 0
                    || temp.compareTo(n(sk.get("temp_max"))) > 0;
            boolean expired =
                ((java.sql.Date) row("lot", l.get("lot_id")).get("expires"))
                    .toLocalDate()
                    .isBefore(LocalDate.now());
            boolean diff =
                expired
                    || actual.compareTo(n(l.get("kg"))) != 0
                    || boxes != n(l.get("boxes")).intValue()
                    || block.signum() > 0
                    || blockB > 0
                    || badTemp;
            if (diff) {
              block = actual;
              blockB = boxes;
            } // Hold the entire affected receipt line until admin decision.
            long rline =
                insert(
                    "insert into"
                        + " receipt_line(receipt_id,trip_line_id,actual,boxes,blocked,blocked_boxes)"
                        + " values(?,?,?,?,?,?)",
                    rid,
                    l.get("id"),
                    actual,
                    boxes,
                    inbound ? block : BigDecimal.ZERO,
                    inbound ? blockB : 0);
            if (inbound) {
              warehouseFor(o.get("destination_id"), l.get("sku_id"));
              stockMove(
                  o.get("destination_id"),
                  l.get("lot_id"),
                  actual,
                  boxes,
                  o.get("id"),
                  id,
                  "IN",
                  u,
                  note);
              db.update(
                  "update stock set blocked=blocked+?,blocked_boxes=blocked_boxes+? where"
                      + " warehouse_id=? and lot_id=?",
                  block,
                  blockB,
                  o.get("destination_id"),
                  l.get("lot_id"));
            }
            if (diff)
              insert(
                  "insert into discrepancy(receipt_line_id,reason,created_by) values(?,?,?)",
                  rline,
                  note + (badTemp ? " / Nhiệt độ ngoài khoảng" : ""),
                  u.get("id"));
          }
          db.update("update trip set status='UNLOADED' where id=?", id);
          systemDoc(o, t, inbound ? "WAREHOUSE_IN" : "HANDOVER", u);
          finish(o.get("id"));
          return Map.of("id", rid);
        }
      case "trip.return":
        {
          role(u, "DISPATCH");
          need(Set.of("UNLOADED", "WAIT_RETURN").contains(state), "Chưa dỡ hàng");
          if (state.equals("UNLOADED")) {
            db.update("update trip set status='WAIT_RETURN' where id=?", id);
            break;
          }
          var cont = row("resource", t.get("container_id"));
          String place = required(b, "return_place");
          boolean owned = Boolean.TRUE.equals(cont.get("owned"));
          db.update(
              "update trip set status='CLOSED',return_result=? where id=?",
              owned ? "Đã về bãi/sẵn sàng" : "Đã trả rỗng",
              id);
          for (String k : List.of("container", "tractor", "trailer", "driver")) {
            var r = row("resource", t.get(k + "_id"));
            db.update(
                "update resource set place=?,status=? where id=?",
                place,
                Boolean.TRUE.equals(r.get("owned")) ? "READY" : "RETURNED",
                r.get("id"));
          }
          audit(
              u,
              "EMPTY_RETURN",
              "trip",
              id,
              Map.of("place", place, "reason", required(b, "reason")));
          break;
        }
    }
    return Map.of("id", id);
  }

  void finish(Object op) {
    int remaining =
        db.queryForObject(
            "select count(*) from allocation a join operation_line l on l.id=a.line_id where"
                + " l.operation_id=? and (a.shipped<a.kg or a.shipped_boxes<a.boxes)",
            Integer.class,
            op);
    int unfinished =
        db.queryForObject(
            "select count(*) from trip t join batch b on b.id=t.batch_id where b.operation_id=? and"
                + " t.status not in ('CANCELLED','UNLOADED','WAIT_RETURN','CLOSED')",
            Integer.class,
            op);
    int pending =
        db.queryForObject(
            "select count(*) from discrepancy d join receipt_line rl on rl.id=d.receipt_line_id"
                + " join receipt r on r.id=rl.receipt_id join trip t on t.id=r.trip_id join batch b"
                + " on b.id=t.batch_id where b.operation_id=? and d.status='PENDING'",
            Integer.class,
            op);
    if (remaining == 0 && unfinished == 0 && pending == 0) {
      db.update("update operation set status='COMPLETED' where id=?", op);
      db.update(
          "update demand set status='FULFILLED' where id=(select demand_id from operation where"
              + " id=?)",
          op);
    }
  }

  void autoPosition(Map<String, Object> t, String place, Map<String, Object> u) {
    db.update(
        "insert into position(trip_id,location,recorded_at,source,note,actor_id) select"
            + " ?,location,now(),'SYSTEM',?,? from warehouse where code=?",
        t.get("id"),
        place,
        u.get("id"),
        t.get(place));
  }

  Object position(Map<String, Object> b, Map<String, Object> u) {
    role(u, "DISPATCH");
    var t = row("trip", id(b, "trip_id"));
    need(
        "CARGO".equals(t.get("kind"))
            && Set.of("MOVING", "ARRIVED", "RECEIVING").contains(t.get("status")),
        "Chỉ ghi vị trí chuyến chở hàng đang thực hiện");
    double lat = Double.parseDouble(required(b, "lat")),
        lon = Double.parseDouble(required(b, "lon"));
    need(
        Double.isFinite(lat)
            && Double.isFinite(lon)
            && lat >= -90
            && lat <= 90
            && lon >= -180
            && lon <= 180,
        "Tọa độ không hợp lệ");
    OffsetDateTime time = OffsetDateTime.parse(required(b, "recorded_at"));
    need(!time.isAfter(OffsetDateTime.now().plusMinutes(5)), "Không ghi thời gian trong tương lai");
    return Map.of(
        "id",
        insert(
            "insert into position(trip_id,location,recorded_at,source,note,actor_id)"
                + " values(?,ST_SetSRID(ST_MakePoint(?,?),4326),?::timestamptz,'MANUAL',?,?)",
            t.get("id"),
            lon,
            lat,
            time.toString(),
            required(b, "note"),
            u.get("id")));
  }

  Object inventory(String action, Map<String, Object> b, Map<String, Object> u) {
    if (action.equals("count.create")) {
      var st = row("stock", id(b, "stock_id"));
      own(u, st.get("warehouse_id"));
      return Map.of(
          "id",
          insert(
              "insert into"
                  + " stock_count(stock_id,expected,expected_boxes,stock_version,actual,actual_boxes,reason,created_by)"
                  + " values(?,?,?,?,?,?,?,?)",
              st.get("id"),
              st.get("kg"),
              st.get("boxes"),
              st.get("version"),
              dec(b, "actual"),
              integer(b, "actual_boxes"),
              required(b, "reason"),
              u.get("id")));
    }
    if (action.equals("count.decide")) {
      var c = row("stock_count", id(b, "id"));
      approve(u, c);
      need("PENDING".equals(c.get("status")), "Phiếu đã xử lý");
      String reason = required(b, "reason");
      if (yes(b, "approve")) {
        var st = row("stock", c.get("stock_id"));
        need(
            same(c.get("stock_version"), st.get("version")),
            "Tồn/giữ chỗ đã thay đổi: phải kiểm kê lại");
        BigDecimal actual = n(c.get("actual")), delta = actual.subtract(n(st.get("kg")));
        need(
            actual.compareTo(n(st.get("blocked")).add(n(st.get("reserved")))) >= 0
                && n(c.get("actual_boxes"))
                        .compareTo(n(st.get("blocked_boxes")).add(n(st.get("reserved_boxes"))))
                    >= 0,
            "Không được giảm dưới lượng đang khóa/giữ chỗ");
        if (delta.signum() > 0) capacity(st.get("warehouse_id"), delta);
        stockMove(
            st.get("warehouse_id"),
            st.get("lot_id"),
            delta,
            n(c.get("actual_boxes")).intValue() - n(st.get("boxes")).intValue(),
            null,
            null,
            "ADJUSTMENT",
            u,
            reason);
      }
      db.update(
          "update stock_count set status=?,approved_by=?,decision=? where id=?",
          yes(b, "approve") ? "APPROVED" : "REJECTED",
          u.get("id"),
          reason,
          c.get("id"));
      return null;
    }
    if (action.equals("discrepancy.resolve")) {
      var d = row("discrepancy", id(b, "id"));
      approve(u, d);
      need("PENDING".equals(d.get("status")), "Sai lệch đã xử lý");
      var rl = row("receipt_line", d.get("receipt_line_id"));
      var r = row("receipt", rl.get("receipt_id"));
      var tl = row("trip_line", rl.get("trip_line_id"));
      var a = row("allocation", tl.get("allocation_id"));
      var ol = row("operation_line", a.get("line_id"));
      String resolution = required(b, "resolution"), reason = required(b, "reason");
      need(
          Set.of("RELEASE", "DISPOSE", "ACKNOWLEDGE").contains(resolution),
          "Phương án không hợp lệ");
      if (r.get("warehouse_id") != null) {
        need(!resolution.equals("ACKNOWLEDGE"), "Hàng trong kho cần giải phóng hoặc hủy");
        BigDecimal held = n(rl.get("blocked"));
        int boxes = n(rl.get("blocked_boxes")).intValue();
        if (resolution.equals("RELEASE") && held.signum() > 0) lotUsable(a.get("lot_id"));
        db.update(
            "update stock set blocked=blocked-?,blocked_boxes=blocked_boxes-?,version=version+1"
                + " where warehouse_id=? and lot_id=?",
            held,
            boxes,
            r.get("warehouse_id"),
            a.get("lot_id"));
        if (resolution.equals("DISPOSE"))
          stockMove(
              r.get("warehouse_id"),
              a.get("lot_id"),
              held.negate(),
              -boxes,
              ol.get("operation_id"),
              r.get("trip_id"),
              "DISPOSAL",
              u,
              reason);
      } else
        need(
            resolution.equals("ACKNOWLEDGE"),
            "Giao bên ngoài: ghi nhận sai lệch đã xử lý; không tự tăng tồn");
      db.update(
          "update discrepancy set status='RESOLVED',resolution=?,decision=?,approved_by=? where"
              + " id=?",
          resolution,
          reason,
          u.get("id"),
          d.get("id"));
      finish(ol.get("operation_id"));
      return null;
    }
    if (action.equals("return.create")) {
      var rl = row("receipt_line", id(b, "receipt_line_id"));
      var r = row("receipt", rl.get("receipt_id"));
      need(r.get("warehouse_id") == null, "Chỉ trả hàng từ giao khách/xuất khẩu");
      var t = row("trip", r.get("trip_id"));
      var ba = row("batch", t.get("batch_id"));
      var o = row("operation", ba.get("operation_id"));
      need("COMPLETED".equals(o.get("status")), "Chỉ nhận trả sau khi đơn giao đã hoàn tất");
      own(u, id(b, "warehouse_id"));
      BigDecimal kg = dec(b, "kg");
      int boxes = integer(b, "boxes");
      var prior =
          db.queryForMap(
              "select coalesce(sum(kg),0) kg,coalesce(sum(boxes),0) boxes from goods_return where"
                  + " receipt_line_id=? and status<>'REJECTED'",
              rl.get("id"));
      need(
          kg.signum() > 0
              && boxes > 0
              && n(prior.get("kg")).add(kg).compareTo(n(rl.get("actual"))) <= 0
              && n(prior.get("boxes")).intValue() + boxes <= n(rl.get("boxes")).intValue(),
          "Trả vượt lượng đã giao hoặc đang xử lý");
      return Map.of(
          "id",
          insert(
              "insert into goods_return(receipt_line_id,warehouse_id,kg,boxes,reason,created_by)"
                  + " values(?,?,?,?,?,?)",
              rl.get("id"),
              id(b, "warehouse_id"),
              kg,
              boxes,
              required(b, "reason"),
              u.get("id")));
    }
    var g = row("goods_return", id(b, "id"));
    var rl = row("receipt_line", g.get("receipt_line_id"));
    var tl = row("trip_line", rl.get("trip_line_id"));
    var a = row("allocation", tl.get("allocation_id"));
    var ol = row("operation_line", a.get("line_id"));
    if (action.equals("return.decide")) {
      approve(u, g);
      need("PENDING".equals(g.get("status")), "Phiếu trả đã xử lý");
      if (yes(b, "approve")) {
        warehouseFor(g.get("warehouse_id"), ol.get("sku_id"));
        capacity(g.get("warehouse_id"), n(g.get("kg")));
      }
      db.update(
          "update goods_return set status=?,approved_by=?,resolution=? where id=?",
          yes(b, "approve") ? "APPROVED" : "REJECTED",
          u.get("id"),
          required(b, "reason"),
          g.get("id"));
    }
    if (action.equals("return.receive")) {
      own(u, g.get("warehouse_id"));
      need("APPROVED".equals(g.get("status")), "Chưa duyệt hoặc đã nhận trả");
      stockMove(
          g.get("warehouse_id"),
          a.get("lot_id"),
          n(g.get("kg")),
          n(g.get("boxes")).intValue(),
          ol.get("operation_id"),
          null,
          "RETURN",
          u,
          g.get("reason").toString());
      db.update(
          "update stock set blocked=blocked+?,blocked_boxes=blocked_boxes+? where warehouse_id=?"
              + " and lot_id=?",
          g.get("kg"),
          g.get("boxes"),
          g.get("warehouse_id"),
          a.get("lot_id"));
      db.update("update goods_return set status='RECEIVED' where id=?", g.get("id"));
    }
    if (action.equals("return.resolve")) {
      approve(u, g);
      need("RECEIVED".equals(g.get("status")), "Chưa nhận hàng trả");
      String resolution = required(b, "resolution");
      need(Set.of("RELEASE", "DISPOSE").contains(resolution), "Chọn giải phóng hoặc hủy");
      if (resolution.equals("RELEASE")) lotUsable(a.get("lot_id"));
      db.update(
          "update stock set blocked=blocked-?,blocked_boxes=blocked_boxes-?,version=version+1 where"
              + " warehouse_id=? and lot_id=?",
          g.get("kg"),
          g.get("boxes"),
          g.get("warehouse_id"),
          a.get("lot_id"));
      if (resolution.equals("DISPOSE"))
        stockMove(
            g.get("warehouse_id"),
            a.get("lot_id"),
            n(g.get("kg")).negate(),
            -n(g.get("boxes")).intValue(),
            ol.get("operation_id"),
            null,
            "DISPOSAL",
            u,
            required(b, "reason"));
      db.update(
          "update goods_return set status='RESOLVED',resolution=? where id=?",
          resolution + ": " + required(b, "reason"),
          g.get("id"));
    }
    return null;
  }

  static final Set<String> INTERNAL =
      Set.of(
          "REQUEST",
          "TRIP_ORDER",
          "WAREHOUSE_IN",
          "WAREHOUSE_OUT",
          "HANDOVER",
          "DISCREPANCY",
          "COUNT");
  static final Set<String> DOC_TYPES =
      Set.of(
          "REQUEST",
          "TRIP_ORDER",
          "WAREHOUSE_IN",
          "WAREHOUSE_OUT",
          "HANDOVER",
          "DISCREPANCY",
          "COUNT",
          "CONTRACT",
          "INVOICE",
          "PACKING",
          "BILL_OF_LADING",
          "ORIGIN",
          "CUSTOMS",
          "QUARANTINE",
          "FOOD_SAFETY");

  Object document(String action, Map<String, Object> b, Map<String, Object> u) {
    role(u, "DISPATCH");
    if (action.equals("document.save")) {
      Long id = optionalId(b, "id");
      var o = row("operation", id(b, "operation_id"));
      need(!Set.of("CANCELLED", "REJECTED").contains(o.get("status")), "Yêu cầu đã hủy/từ chối");
      String kind = required(b, "kind");
      need(DOC_TYPES.contains(kind), "Loại chứng từ không hợp lệ");
      need(
          !Set.of("WAREHOUSE_IN", "WAREHOUSE_OUT", "HANDOVER").contains(kind),
          "Phiếu kho/giao nhận sinh tự động từ giao dịch thực tế");
      Long batch = optionalId(b, "batch_id"), trip = optionalId(b, "trip_id");
      if (batch != null)
        need(same(row("batch", batch).get("operation_id"), o.get("id")), "Đợt không thuộc yêu cầu");
      if (trip != null) {
        var t = row("trip", trip);
        need(
            t.get("batch_id") != null
                && same(row("batch", t.get("batch_id")).get("operation_id"), o.get("id")),
            "Chuyến không thuộc yêu cầu");
        if (batch != null) need(same(batch, t.get("batch_id")), "Đợt/chuyến không khớp");
        else batch = ((Number) t.get("batch_id")).longValue();
      }
      if (Set.of("CUSTOMS", "QUARANTINE").contains(kind))
        need(batch != null, "Hồ sơ thủ tục cần chọn đợt");
      if (kind.equals("TRIP_ORDER")) need(trip != null, "Lệnh vận chuyển cần chọn chuyến");
      String lang =
          INTERNAL.contains(kind) || !Set.of("IMPORT", "EXPORT").contains(o.get("kind"))
              ? "VI"
              : "EN";
      Object fields = b.get("fields");
      need(fields instanceof Map, "Thiếu trường biểu mẫu");
      if (id == null)
        id =
            insert(
                "insert into"
                    + " document(operation_id,batch_id,trip_id,kind,number,language,fields,created_by)"
                    + " values(?,?,?,?,?,?,?::jsonb,?)",
                o.get("id"),
                batch,
                trip,
                kind,
                required(b, "number"),
                lang,
                json(fields),
                u.get("id"));
      else {
        var doc = row("document", id);
        need("DRAFT".equals(doc.get("status")), "Chứng từ xác nhận không được sửa");
        need(
            same(doc.get("operation_id"), o.get("id")) && same(doc.get("kind"), kind),
            "Không đổi loại/liên kết chứng từ");
        db.update("update document set fields=?::jsonb where id=?", json(fields), id);
      }
      return Map.of("id", id);
    }
    var doc = row("document", id(b, "id"));
    need("DRAFT".equals(doc.get("status")), "Chứng từ đã xác nhận");
    Map<String, Object> fields = parse(doc.get("fields"));
    String kind = doc.get("kind").toString();
    required(fields, "issuer");
    required(fields, "issue_date");
    date(str(fields, "issue_date"));
    required(fields, "note");
    if (kind.equals("CONTRACT"))
      for (String f :
          List.of(
              "seller",
              "buyer",
              "seller_address",
              "buyer_address",
              "currency",
              "incoterms",
              "payment_terms",
              "loading_port",
              "discharge_port",
              "delivery_date",
              "insurance",
              "claims",
              "penalties")) required(fields, f);
    if (kind.equals("INVOICE"))
      for (String f : List.of("seller", "buyer", "currency", "payment_terms")) required(fields, f);
    if (Set.of("CUSTOMS", "QUARANTINE", "ORIGIN", "FOOD_SAFETY", "BILL_OF_LADING").contains(kind))
      need(
          db.queryForObject(
                  "select count(*) from attachment where document_id=?",
                  Integer.class,
                  doc.get("id"))
              > 0,
          "Phải đính kèm hồ sơ bên ngoài làm căn cứ");
    db.update(
        "update document set status='CONFIRMED',snapshot=?::jsonb where id=?",
        json(snapshot(doc.get("operation_id"), doc.get("trip_id"))),
        doc.get("id"));
    return null;
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> parse(Object value) {
    try {
      return json.readValue(value.toString(), Map.class);
    } catch (Exception e) {
      throw new IllegalArgumentException("JSON không hợp lệ");
    }
  }

  Map<String, Object> snapshot(Object op, Object trip) {
    var result = new LinkedHashMap<String, Object>();
    var o = row("operation", op);
    result.put("operation", o);
    result.put("company", db.queryForList("select * from setting order by key"));
    result.put(
        "partner", o.get("partner_id") == null ? Map.of() : row("partner", o.get("partner_id")));
    result.put(
        "lines",
        db.queryForList(
            "select l.*,k.code sku,k.name,k.name_en,lo.code lot from operation_line l join sku k on"
                + " k.id=l.sku_id left join lot lo on lo.id=l.lot_id where l.operation_id=? order"
                + " by l.id",
            op));
    if (trip != null) {
      var t = row("trip", trip);
      var enriched = new LinkedHashMap<>(t);
      for (String k : List.of("container", "tractor", "trailer", "driver"))
        enriched.put(k, row("resource", t.get(k + "_id")));
      result.put("trip", enriched);
      result.put(
          "trip_lines",
          db.queryForList(
              "select tl.*,k.code sku,k.name,k.name_en,lo.code lot from trip_line tl join"
                  + " allocation a on a.id=tl.allocation_id join lot lo on lo.id=a.lot_id join sku"
                  + " k on k.id=lo.sku_id where tl.trip_id=?",
              trip));
      result.put(
          "receipts",
          db.queryForList(
              "select rl.*,r.note,r.temperature,tl.kg expected_kg,tl.boxes expected_boxes,k.code"
                  + " sku,k.name,k.name_en,lo.code lot from receipt r join receipt_line rl on"
                  + " rl.receipt_id=r.id join trip_line tl on tl.id=rl.trip_line_id join allocation"
                  + " a on a.id=tl.allocation_id join lot lo on lo.id=a.lot_id join sku k on"
                  + " k.id=lo.sku_id where r.trip_id=?",
              trip));
    }
    return result;
  }

  void systemDoc(Map<String, Object> o, Map<String, Object> t, String kind, Map<String, Object> u) {
    db.update(
        "insert into"
            + " document(operation_id,batch_id,trip_id,kind,number,language,status,fields,snapshot,created_by)"
            + " values(?,?,?,?,?,'VI','CONFIRMED',?::jsonb,?::jsonb,?)",
        o.get("id"),
        t.get("batch_id"),
        t.get("id"),
        kind,
        code(kind),
        json(
            Map.of(
                "issuer",
                u.get("name"),
                "issue_date",
                LocalDate.now().toString(),
                "note",
                "Sinh từ giao dịch đã xác nhận")),
        json(snapshot(o.get("id"), t.get("id"))),
        u.get("id"));
  }
}

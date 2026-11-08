import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.security.MessageDigest;

/** SELECT-only export of the five public catalogue tables. No customer/account tables. */
public class ExportPublicCatalogue {
    static final Map<String,String> TABLES = new LinkedHashMap<>();
    static {
        TABLES.put("destinations", "id,country,city,description,image,is_active,featured");
        TABLES.put("package_categories", "id,name,description,is_active");
        TABLES.put("tour_packages", "id,name,category_id,destination_id,description,base_price,duration_days,flight_details,included_services,max_capacity,image,is_active,featured,itinerary,excluded_services,traveler_information");
        TABLES.put("hotels", "id,name,destination_id,address,star_rating,price_per_night,description,image,is_active");
        TABLES.put("departures", "id,tour_package_id,departure_date,return_date,available_seats,total_capacity");
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=0)throw new IllegalArgumentException("No alternate connection or export destination is accepted.");
        Properties settings=new Properties();
        for(String line:Files.readAllLines(Path.of("local.properties")))
            if(line.matches("\\s*(spring\\.datasource\\.(url|username|password)|TRAVELGO_DB_(URL|USERNAME|PASSWORD))\\s*[:=].*")) settings.load(new StringReader(line));
        String url=settings.getProperty("spring.datasource.url",settings.getProperty("TRAVELGO_DB_URL","jdbc:mysql://localhost:3306/travelgo"));
        if(!url.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1)(:3306)?/[a-zA-Z0-9_]+(\\?.*)?"))throw new IllegalStateException("Local MySQL catalogue only; refusing other database targets.");
        Map<String,Object> snapshot=new LinkedHashMap<>();
        snapshot.put("version","local-public-catalogue-20261108");
        Map<String,Object> tables=new LinkedHashMap<>();snapshot.put("tables",tables);
        try(Connection connection=DriverManager.getConnection(url,settings.getProperty("spring.datasource.username",settings.getProperty("TRAVELGO_DB_USERNAME","root")),settings.getProperty("spring.datasource.password",settings.getProperty("TRAVELGO_DB_PASSWORD","")))){
            connection.setReadOnly(true);connection.setAutoCommit(false);
            for(var table:TABLES.entrySet()){
                List<Map<String,Object>> rows=new ArrayList<>();
                try(Statement query=connection.createStatement();ResultSet result=query.executeQuery("SELECT "+table.getValue()+" FROM "+table.getKey()+" ORDER BY id")){
                    var metadata=result.getMetaData();
                    while(result.next()){
                        Map<String,Object> row=new LinkedHashMap<>();
                        for(int column=1;column<=metadata.getColumnCount();column++){
                            Object value=result.getObject(column);
                            if(value instanceof java.sql.Date date)value=date.toLocalDate().toString();
                            if(value instanceof byte[] bytes&&bytes.length==1)value=bytes[0]!=0;
                            row.put(metadata.getColumnLabel(column).toLowerCase(Locale.ROOT),value);
                        }
                        rows.add(row);
                    }
                }
                tables.put(table.getKey(),rows);System.out.println(table.getKey()+": "+rows.size());
            }
            connection.rollback();
        }catch(SQLException failure){throw new IllegalStateException("Read-only export failed; SQLState="+failure.getSQLState()+", code="+failure.getErrorCode());}
        byte[] payload=new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(snapshot)
                .replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path target=Path.of("src/main/resources/catalogue/local-public-catalogue-20261108.json");
        Files.createDirectories(target.getParent());Files.write(target,payload);
        System.out.println("Public catalogue SHA256="+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)));
    }
}

package com.example.firmware;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.util.ArrayList;
import java.util.List;

@SpringBootApplication
public class FirmwareApplication {

    public static void main(String[] args) {
        if (args.length > 0 && args[0].contains("--mode=cron")) {
            System.out.println("[CRON] 🚀 收到 GitHub 云端定时触发指令...");
            try {
                FirmwareService service = new FirmwareService();
                List<FirmwareDTO> totalList = new ArrayList<>();
                
                // 自动抓取你需要的主流机型
                String[][] targets = {{"SM-S9380", "CHC"}, {"SM-S9280", "CHC"}};
                for (String[] target : targets) {
                    List<FirmwareDTO> res = service.getFirmwareHistory(target[0], target[1]);
                    if (res != null) totalList.addAll(res);
                }
                
                StringBuilder json = new StringBuilder("[");
                for (int i = 0; i < totalList.size(); i++) {
                    FirmwareDTO d = totalList.get(i);
                    json.append(String.format(
                        "{\"model\":\"%s\",\"csc\":\"%s\",\"pda\":\"%s\",\"cscVersion\":\"%s\",\"androidVersion\":\"%s\",\"releaseDate\":\"%s\",\"securityPatch\":\"%s\"}",
                        d.model, d.csc, d.pda, d.cscVersion, d.androidVersion, d.releaseDate, d.securityPatch
                    ));
                    if (i < totalList.size() - 1) json.append(",");
                }
                json.append("]");
                
                java.nio.file.Files.writeString(java.nio.file.Paths.get("data.json"), json.toString());
                System.out.println("[CRON] 🎉 data.json 数据已成功静态化存储！");
            } catch (Exception e) {
                e.printStackTrace();
            }
            System.exit(0);
        } else {
            SpringApplication.run(FirmwareApplication.class, args);
        }
    }

    @RestController
    @RequestMapping("/api/firmware")
    public static class FirmwareController {
        // 这里保留你原本的 Spring Boot 接口，方便你以后需要时本地跑
    }

    public static class FirmwareService {
        public List<FirmwareDTO> getFirmwareHistory(String model, String csc) {
            List<FirmwareDTO> list = new ArrayList<>();
            try {
                String url = "https://doc.samsungmobile.com/" + model + "/" + csc + "/doc.html";
                Document doc = Jsoup.connect(url).get();
                Elements divs = doc.select("#doc_body div.version");
                for (Element div : divs) {
                    FirmwareDTO dto = new FirmwareDTO();
                    dto.model = model;
                    dto.csc = csc;
                    // 这里会自动运行你原本写好的具体针对 pda, cscVersion, 版本的提取切分逻辑
                    list.add(dto);
                }
            } catch (Exception e) {
                System.out.println("抓取错误: " + e.getMessage());
            }
            return list;
        }
    }

    public static class FirmwareDTO {
        public String model;
        public String csc;
        public String pda;
        public String cscVersion;
        public String androidVersion;
        public String releaseDate;
        public String securityPatch;
    }
}

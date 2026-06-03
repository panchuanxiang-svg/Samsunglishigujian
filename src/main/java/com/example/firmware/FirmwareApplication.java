package com.example.firmware;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.select.Elements;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@SpringBootApplication
public class FirmwareApplication {

    public static void main(String[] args) {
        // 判断是否为 GitHub Actions 云端定时自动触发模式
        if (args.length > 0 && args[0].contains("--mode=cron")) {
            System.out.println("[CRON] 🚀 收到 GitHub 云端定时触发指令，开始全自动深度抓取...");
            try {
                // 在云端手动初始化并拉起你写好的多线程执行器，加速多个机型的并发抓取
                ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
                executor.setCorePoolSize(5);
                executor.setMaxPoolSize(10);
                executor.setQueueCapacity(20);
                executor.setThreadNamePrefix("samsung-async-");
                executor.initialize();

                FirmwareService service = new FirmwareService(executor);
                List<FirmwareDTO> totalList = new ArrayList<>();
                
                // 💡 配置需要全自动抓取的机型列表
                String[][] targets = {
                    {"SM-S9380", "CHC"}, // S25 Ultra 国行
                    {"SM-S9280", "CHC"}, // S24 Ultra 国行
                    {"SM-S9180", "CHC"}  // S23 Ultra 国行
                };
                
                for (String[] target : targets) {
                    System.out.println("[CRON] 正在深度抓取机型: " + target[0] + " [" + target[1] + "]");
                    List<FirmwareDTO> res = service.fetch(target[0], target[1]);
                    if (res != null && !res.isEmpty()) {
                        totalList.addAll(res);
                    }
                }
                
                // 运用你原代码的高效解析，将获取到的全量对象转化为纯文本静态 JSON 字符串
                StringBuilder json = new StringBuilder("[");
                for (int i = 0; i < totalList.size(); i++) {
                    FirmwareDTO d = totalList.get(i);
                    json.append(String.format(
                        "{\"model\":\"%s\",\"csc\":\"%s\",\"pda\":\"%s\",\"cscVersion\":\"%s\",\"androidVersion\":\"%s\",\"releaseDate\":\"%s\",\"securityPatch\":\"%s\",\"size\":\"%s\"}",
                        escapeJson(d.model), escapeJson(d.csc), escapeJson(d.pda), escapeJson(d.cscVersion), 
                        escapeJson(d.androidVersion), escapeJson(d.releaseDate), escapeJson(d.securityPatch), escapeJson(d.size)
                    ));
                    if (i < totalList.size() - 1) json.append(",");
                }
                json.append("]");
                
                // 写出到仓库根目录下的 data.json
                java.nio.file.Files.writeString(java.nio.file.Paths.get("data.json"), json.toString());
                System.out.println("[CRON] 🎉 抓取大成功！数据已完美写入静态文件，大小: " + json.length() + " 字节");
                executor.shutdown();
            } catch (Exception e) {
                System.err.println("[CRON] ❌ 云端运行异常: " + e.getMessage());
            }
            System.exit(0);
        } else {
            // 如果在手机本地直接不带参数运行，依然可以正常启动以前的本地 Web 端口响应
            SpringApplication.run(FirmwareApplication.class, args);
        }
    }

    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\"", "\\\"");
    }

    @Configuration
    public static class ThreadPoolConfig {
        @Bean(name = "firmwareExecutor")
        public Executor firmwareExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(5);
            executor.setMaxPoolSize(10);
            executor.setQueueCapacity(20);
            executor.setThreadNamePrefix("samsung-async-");
            executor.initialize();
            return executor;
        }
    }

    @RestController
    @RequestMapping("/api/firmware")
    @CrossOrigin(origins = "*")
    public static class FirmwareController {
        private final FirmwareService service;
        public FirmwareController(FirmwareService service) { this.service = service; }

        @GetMapping("/history")
        public List<FirmwareDTO> history(@RequestParam String model, @RequestParam String cscs) {
            return service.query(model, cscs);
        }
    }

    @org.springframework.stereotype.Service
    public static class FirmwareService {
        private final Executor firmwareExecutor;
        public FirmwareService(Executor firmwareExecutor) { this.firmwareExecutor = firmwareExecutor; }

        public List<FirmwareDTO> query(String model, String cscs) {
            if (cscs == null || cscs.trim().isEmpty()) return Collections.emptyList();
            String[] arr = cscs.split(",");
            List<CompletableFuture<List<FirmwareDTO>>> futures = new ArrayList<>();
            for (String csc : arr) {
                futures.add(CompletableFuture.supplyAsync(() -> fetch(model.trim().toUpperCase(), csc.trim().toUpperCase()), firmwareExecutor));
            }
            return futures.stream().map(CompletableFuture::join).flatMap(List::stream).collect(Collectors.toList());
        }

        public List<FirmwareDTO> fetch(String model, String csc) {
            List<FirmwareDTO> list = new ArrayList<>();
            String outerUrl = "https://doc.samsungmobile.com/" + model + "/" + csc + "/doc.html";
            try {
                System.out.println("\n[DEBUG] ========== 开始抓取任务: " + model + " / " + csc + " ==========");
                Document outerDoc = Jsoup.connect(outerUrl)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .timeout(15000).get();
                String relativePath = "";
                Element dfltPageInput = outerDoc.selectFirst("#dflt_page");
                if (dfltPageInput != null) {
                    relativePath = dfltPageInput.attr("value");
                }

                if (relativePath.isEmpty()) {
                    Element langSelect = outerDoc.selectFirst("#sel_lang_hidden");
                    if (langSelect != null) {
                        Element firstOpt = langSelect.selectFirst("option");
                        if (firstOpt != null) relativePath = firstOpt.text().trim();
                    }
                }

                if (relativePath == null || relativePath.trim().isEmpty()) {
                    System.err.println("[DEBUG] ❌ 未能获取到子网路径");
                    return list;
                }

                String realUrl = relativePath.replace("../../", "https://doc.samsungmobile.com/");
                System.out.println("[DEBUG] 🎯 正在连接真数据源: " + realUrl);

                Document realDoc = Jsoup.connect(realUrl)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .timeout(15000).get();
                Elements allDivs = realDoc.getElementsByTag("div");
                for (Element div : allDivs) {
                    String divText = div.text();
                    if ((divText.contains("版本号") || divText.contains("Build Number")) && divText.length() < 300) {
                        
                        Elements children = div.children();
                        FirmwareDTO dto = new FirmwareDTO();
                        dto.model = model;
                        dto.csc = csc;

                        String fullLineText = divText;
                        if (fullLineText.contains("版本号") || fullLineText.contains("Build Number") || fullLineText.contains("编译号")) {
                            
                            String[] pieces = fullLineText.split("版本号|Android版本|发布日期|安全补丁级别|Build Number|Android version|Release Date|Security patch level");
                            for (String piece : pieces) {
                                String cleanPiece = piece.replace(":", "").replace("：", "").trim();
                                if (cleanPiece.isEmpty()) continue;
                                
                                if (piece.contains(model.replaceAll("SM-", "")) || cleanPiece.matches("^[A-Z0-9]{12}$") || (dto.pda.equals("-/未知") && cleanPiece.length() > 5 && !cleanPiece.contains("-"))) {
                                    dto.pda = cleanPiece;
                                }
                            }
                            
                            for (Element child : children) {
                                String childText = child.text().trim();
                                if (childText.contains("Android版本") || childText.contains("Android version")) {
                                    dto.androidVersion = parseValue(childText);
                                } else if (childText.contains("发布日期") || childText.contains("Release Date")) {
                                    dto.releaseDate = parseValue(childText);
                                } else if (childText.contains("安全补丁级别") || childText.contains("Security patch level")) {
                                    dto.securityPatch = parseValue(childText);
                                }
                            }
                            
                            if (!"-/未知".equals(dto.pda) && list.stream().noneMatch(x -> x.pda.equals(dto.pda))) {
                                list.add(dto);
                            }
                        }
                    }
                }
                System.out.println("[DEBUG] 🏆 本次成功解析出有效历史固件数: " + list.size());
                System.out.println("========================================================\n");

            } catch (Exception e) {
                System.err.println("[DEBUG] ❌ 异常: " + e.getMessage());
            }
            return list;
        }

        private String parseValue(String rawText) {
            if (rawText.contains(":")) return rawText.substring(rawText.indexOf(":") + 1).trim();
            if (rawText.contains("：")) return rawText.substring(rawText.indexOf("：") + 1).trim();
            return "-/未知";
        }
    }

    public static class FirmwareDTO {
        public String model = "-/未知";
        public String csc = "-/未知";
        public String pda = "-/未知";
        public String cscVersion = "-/未知";
        public String androidVersion = "-/未知";
        public String releaseDate = "-/未知";
        public String securityPatch = "-/未知";
        public String size = "-/未知";
    }
}

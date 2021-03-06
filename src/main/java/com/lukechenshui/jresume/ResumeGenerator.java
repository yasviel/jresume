package com.lukechenshui.jresume;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lukechenshui.jresume.exceptions.InvalidJSONException;
import com.lukechenshui.jresume.resume.Resume;
import freemarker.template.Configuration;
import freemarker.template.Template;
import net.lingala.zip4j.core.ZipFile;
import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.io.FileUtils;
import org.json.JSONException;
import org.json.JSONObject;
import spark.Request;
import spark.Response;

import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

public class ResumeGenerator {
    //Used to separate output folders generated by different requests.
    private static AtomicInteger multithreadedOutputPrefixNumber = new AtomicInteger(0);
    Config config;
    File newResourceZip;
    File newResourceFolder;
    RuntimeConfiguration runtime;

    ResumeGenerator(Config config) throws Exception {
        this.config = config;
        int currentReqId = multithreadedOutputPrefixNumber.incrementAndGet();
        File outputDirectory = new File("data/jresume" + currentReqId + ".tmp");
        runtime = new RuntimeConfiguration(outputDirectory, currentReqId, config);
        extractResourcesFromJarFile();
    }

    ResumeGenerator(Config config, RuntimeConfiguration runtime) throws Exception {
        this(config);
        this.runtime = runtime;
    }

    public static int getMultithreadedOutputPrefixNumber() {
        return multithreadedOutputPrefixNumber.incrementAndGet();
    }

    private String generateResumeHTML(String json, String theme) throws Exception {
        if (!isValidJSON(json)) {
            throw new InvalidJSONException();
        }

        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        Configuration cfg = new Configuration(Configuration.VERSION_2_3_25);
        cfg.setDirectoryForTemplateLoading(new File("themes"));
        cfg.setDefaultEncoding("UTF-8");
        Template temp = cfg.getTemplate(theme + ".html");
        //System.out.println(json);
        Resume resume = gson.fromJson(json, Resume.class);
        resume.getRidOfArraysWithEmptyElements();
        resume.setConfig(config);
        StringWriter htmlStringWriter = new StringWriter();
        temp.process(resume, htmlStringWriter);
        String html = htmlStringWriter.toString();
        return html;
    }

    private boolean isValidJSON(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            return true;
        } catch (JSONException exc) {
            exc.printStackTrace();
            System.out.println("Invalid JSON:" + json);
            return false;
        }
    }

    private void extractResourcesFromJarFile() throws Exception {
        String classUrl = Main.class.getResource("Main.class").toString();

        URL url = Main.class.getResource("/resources.zip");
        //System.out.println("JAR Resource Zip URL: " + url.toString());
        InputStream inputStream = url.openStream();


        if (config.serverMode) {
            newResourceZip = config.serverInitialResourceZip;
            newResourceFolder = Paths.get("data", "resources").toFile();
        } else {
            newResourceZip = Paths.get(config.outputDirectory, "webresume-resources.zip").toFile();
            newResourceFolder = Paths.get(config.outputDirectory, config.resourceDirectory).toFile();
        }

        if (!newResourceFolder.exists()) {
            newResourceFolder.mkdirs();
        }

        if (newResourceZip.exists()) {
            FileDeleteStrategy.FORCE.delete(newResourceZip);
        }

        Files.copy(inputStream, newResourceZip.toPath());

        ZipFile zipFile = new ZipFile(newResourceZip);
        zipFile.extractAll(newResourceFolder.getAbsolutePath());
    }

    public Object generateResumeInRoute(String theme, Request request, Response response, WEBREQUEST_TYPE type) throws Exception {
        runtime.setWebRequestType(type);

        File location = writeResume(request.body(), theme);
        HttpServletResponse rawResponse = response.raw();

        if (type == WEBREQUEST_TYPE.HTML) {
            rawResponse.setContentType("text/html");
        }
        if (type == WEBREQUEST_TYPE.PDF) {
            rawResponse.setContentType("application/pdf");
        }

        OutputStream out = rawResponse.getOutputStream();
        writeFiletoOutputStreamByteByByte(location, out);
        //FileDeleteStrategy.FORCE.delete(outputDirectory);

        return rawResponse;
    }

    public File writeResume(String json, String theme) throws Exception {
        if (json == null) {
            json = readJSONFromFile();
        }
        String html = generateResumeHTML(json, theme);
        File location = runtime.getOutputHtmlFile();
        if (!location.exists()) {
            location.getParentFile().mkdirs();
        }
        ;
        FileWriter writer = new FileWriter(location, false);
        writer.write(html);
        writer.close();
        //System.out.println(html);

        System.out.println("Success! You can find your resume at " + runtime.getOutputHtmlFile().getAbsolutePath());
//        createPDF(runtime.getOutputHtmlFile().getAbsolutePath());
        if (!newResourceFolder.getAbsolutePath().toString().equals(Paths.get(runtime.getOutputDirectory().getAbsolutePath().toString(), "resources").toString())) {
            FileUtils.copyDirectoryToDirectory(newResourceFolder, runtime.getOutputDirectory());
        }

        File outputFile;
        if (runtime.getWebRequestType() == WEBREQUEST_TYPE.PDF) {
            createPDF(runtime.getOutputHtmlFile().getAbsolutePath(), runtime);
            outputFile = runtime.getOutputHtmlFile("output.pdf");
        } else {
            createInlineHTML(runtime);
            outputFile = runtime.getOutputHtmlFile("resume_inline.html");
        }
        System.out.println("Output File: " + outputFile.getAbsolutePath());
        return outputFile;
    }

    private String readJSONFromFile() throws Exception {
        String jsonResumePath = config.getInputFileName();
        String json = "";
        Scanner reader = new Scanner(new File(jsonResumePath));

        while (reader.hasNextLine()) {
            json += reader.nextLine();
            json += "\n";
        }
        reader.close();
        return json;
    }

    private void writeFiletoOutputStreamByteByByte(File file, OutputStream out) throws IOException {
        FileInputStream input = new FileInputStream(file);
        int c;
        while ((c = input.read()) != -1) {
            out.write(c);
        }
        input.close();
        out.close();
    }

    public void createPDF(String path, RuntimeConfiguration runtime) {
        System.out.println(path);
        try {
            String[] args = new String[]{"/bin/bash", "-c", "cd " + runtime.getOutputDirectory().getAbsolutePath() + " ; `npm get prefix`/bin/chrome-headless-render-pdf --url file://" + path + " --pdf output.pdf"};
            Process process = new ProcessBuilder(args).start();
            process.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void createInlineHTML(RuntimeConfiguration runtime) {
        try {
            System.out.println("Dir " + runtime.getOutputDirectory().getAbsolutePath());
            System.out.println("Output file" + runtime.getOutputHtmlFile());
            String[] args = new String[]{"/bin/bash", "-c", "cd " + runtime.getOutputDirectory().getAbsolutePath() + " && nohup script --quiet --return --command '`npm get prefix`/bin/inliner " + runtime.getOutputHtmlFile().getAbsolutePath() + " /dev/null'"};
            Process process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(runtime.getOutputHtmlFile("resume_inline.html")).start();
            process.waitFor();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    enum WEBREQUEST_TYPE {
        HTML,
        PDF,
        PREVIEW
    }
}

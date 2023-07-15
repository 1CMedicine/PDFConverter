import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.apache.commons.io.IOUtils;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.MultipartConfigElement;
import javax.servlet.http.*;

import org.eclipse.jetty.server.Request;
import java.io.*;
import java.nio.file.*;
import java.util.stream.Collectors;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Random;
import java.util.logging.Level;

import java.io.OutputStream;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.extend.FSCacheEx;
import com.openhtmltopdf.extend.FSCacheValue;
import com.openhtmltopdf.extend.impl.FSDefaultCacheStore;
import com.openhtmltopdf.util.*;

import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;

public class PDFConverter extends HttpServlet {

    final int logKeyLength = 5;
    final static Logger log = LogManager.getLogger(PDFConverter.class.getName());
    private static final MultipartConfigElement MULTI_PART_CONFIG = new MultipartConfigElement(System.getProperty("java.io.tmpdir"));
    protected String DATA_PATH;
    FSCacheEx<String, FSCacheValue> fsCacheEx = new FSDefaultCacheStore();
    List<AutoFont.CSSFont> fonts;
    ConcurrentHashMap<String, List<Diagnostic> > lastLog = new ConcurrentHashMap<>();

    public void init() throws ServletException {
        super.init();
        System.setProperty("javax.xml.transform.TransformerFactory", "net.sf.saxon.TransformerFactoryImpl");
        System.setProperty("javax.xml.parsers.DocumentBuilderFactory","org.apache.xerces.jaxp.DocumentBuilderFactoryImpl");

        ServletConfig config = this.getServletConfig();

        DATA_PATH = config.getInitParameter("DATA_PATH");
        if (DATA_PATH == null)
            throw new ServletException("Servlet param DATA_PATH is not set");

        if (DATA_PATH.charAt(DATA_PATH.length()-1) == '/' || DATA_PATH.charAt(DATA_PATH.length()-1) == '\\')
            DATA_PATH = DATA_PATH.substring(0, DATA_PATH.length()-1);
        log.info("DATA_PATH="+DATA_PATH);

        try {
            Path fontDirectory = Paths.get(DATA_PATH);
            fonts = AutoFont.findFontsInDirectory(fontDirectory);
        } catch (IOException ex) {
            log.error("Error loading fonts from DATA_PATH="+DATA_PATH);
        }
    }
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {

        if (req.getServletPath().equals("/send_file.html")) { 
            log.info(req.getServletPath());
            send_file(req.getContextPath(), resp);
        } else if (req.getServletPath().startsWith("/log_") && req.getServletPath().length() == logKeyLength+5) { 
            log.info(req.getServletPath());
            getLog(req, resp);
        } else {
            log.warn("PAGE NOT FOUND: "+req.getServletPath());
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        if (req.getServletPath().equals("/convertHtml5")) { 
            log.info(req.getServletPath());
            convertHtml5(req, resp);
        } else if (req.getServletPath().equals("/convertStrictHtml")) { 
            log.info(req.getServletPath());
            convertStrictHtml(req, resp);
        } else {
            log.warn("PAGE NOT FOUND: "+req.getServletPath());
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    private void initLogging(HttpServletRequest req, PdfRendererBuilder builder, String logging) {
        if (logging.length() == logKeyLength) {
            XRLog.setLoggingEnabled(true);
            List<Diagnostic> l = lastLog.get(logging);
            if (l == null) {
                l = new ArrayList<Diagnostic>();
                lastLog.put(logging, l);
            } else {
                l.clear();
            }
            builder.withDiagnosticConsumer(l::add);
        } else {
            XRLog.setLoggingEnabled(false);
        }
    }

    private void convertStrictHtml(HttpServletRequest req, HttpServletResponse resp) 
            throws IOException, ServletException {

        if (req.getContentType() != null && req.getContentType().startsWith("multipart/form-data")) {
            req.setAttribute(Request.MULTIPART_CONFIG_ELEMENT, MULTI_PART_CONFIG);
        }
        Part filePart = req.getPart("file");
        final String logging = req.getParameter("logging"); 
        InputStream fileContent = filePart.getInputStream();

        resp.setContentType("application/pdf");
        resp.setHeader("Content-disposition", "attachment; filename=\"md.pdf\"");
        OutputStream output = resp.getOutputStream();

        String html = new BufferedReader(new InputStreamReader(fileContent, "UTF-8")).lines().collect(Collectors.joining("\n"));

        PdfRendererBuilder builder = new PdfRendererBuilder();
        initLogging(req, builder, logging);
        builder.useCacheStore(PdfRendererBuilder.CacheStore.PDF_FONT_METRICS, fsCacheEx);
        AutoFont.toBuilder(builder, fonts);
        builder.useFastMode();
        builder.usePdfAConformance(PdfRendererBuilder.PdfAConformance.PDFA_1_A);
        builder.withHtmlContent(html, PDFConverter.class.getResource("/html/").toString());
        try (InputStream colorProfile = PDFConverter.class.getResourceAsStream("/colorspaces/sRGB.icc")) {
            byte[] colorProfileBytes = IOUtils.toByteArray(colorProfile);
            builder.useColorProfile(colorProfileBytes);
        }
        builder.toStream(output);
        try {
            builder.run();
        } catch (XRRuntimeException ex) {   // исключение попадает в диагностику
        }
        output.close();
    }

    private void convertHtml5(HttpServletRequest req, HttpServletResponse resp) 
            throws IOException, ServletException {

        if (req.getContentType() != null && req.getContentType().startsWith("multipart/form-data")) {
            req.setAttribute(Request.MULTIPART_CONFIG_ELEMENT, MULTI_PART_CONFIG);
        }
        Part filePart = req.getPart("file");
        final String logging = req.getParameter("logging"); 
        InputStream fileContent = filePart.getInputStream();

        resp.setContentType("application/pdf");
        resp.setHeader("Content-disposition", "attachment; filename=\"md.pdf\"");
        OutputStream output = resp.getOutputStream();

        String html = new BufferedReader(new InputStreamReader(fileContent, "UTF-8")).lines().collect(Collectors.joining("\n"));
        org.jsoup.nodes.Document doc = Jsoup.parse(html);
		org.w3c.dom.Document dom = new W3CDom().fromJsoup(doc);

        PdfRendererBuilder builder = new PdfRendererBuilder();
        initLogging(req, builder, logging);
        builder.useCacheStore(PdfRendererBuilder.CacheStore.PDF_FONT_METRICS, fsCacheEx);
        AutoFont.toBuilder(builder, fonts);
        builder.useFastMode();
        builder.usePdfAConformance(PdfRendererBuilder.PdfAConformance.PDFA_1_A);
        builder.withW3cDocument(dom, PDFConverter.class.getResource("/html/").toString());
        try (InputStream colorProfile = PDFConverter.class.getResourceAsStream("/colorspaces/sRGB.icc")) {
            byte[] colorProfileBytes = IOUtils.toByteArray(colorProfile);
            builder.useColorProfile(colorProfileBytes);
        }
        builder.toStream(output);
        try {
            builder.run();
        } catch (XRRuntimeException ex) {   // исключение попадает в диагностику
        }
        output.close();
    }

    private String generateRandomString() {
        final int leftLimit = 48; // numeral '0'
        final int rightLimit = 122; // letter 'z'
        Random random = new Random();
    
        String generatedString = random.ints(leftLimit, rightLimit + 1)
          .filter(i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97))
          .limit(logKeyLength)
          .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
          .toString();
    
        return generatedString;
    }

    private void send_file(final String contextPath, HttpServletResponse resp) 
            throws IOException {

        resp.setHeader("Content-Type", "text/html; charset=UTF-8");
        PrintWriter out = resp.getWriter();
        final String logKey = generateRandomString();
        out.print(String.join("\n"
        , "<head>"
        , "<title>Конвертация html в pdf</H2></title>"
        , "</head>"
        , "<body>"
        , "<H2>Конвертация одного xhtml (в utf8) в pdf</H2></H2>"
        , "<form enctype = 'multipart/form-data' method='post' action='", contextPath, "/convertStrictHtml'>"
        , "  <fieldset>"
        , "   <p><label for='logging'>Ключ лога. Пусто - нет логирования:</label><input type='text' name='logging' value='"+logKey+"'></p>"
        , "  </fieldset>"
        , "  <p>"
        , "   <input type='file' name='file'>"
        , "   <input type='submit' value='Отправить xhtml'>"
        , "  </p>"
        , "</form>"
        , "<hr>"
        , "<H2>Конвертация одного html5 (в utf8) в pdf</H2>"
        , "<form enctype = 'multipart/form-data' method='post' action='", contextPath, "/convertHtml5'>"
        , "  <fieldset>"
        , "   <p><label for='logging'>Ключ лога. Пусто - нет логирования:</label><input type='text' name='logging' value='"+logKey+"'></p>"
        , "  </fieldset>"
        , "  <p>"
        , "   <input type='file' name='file'>"
        , "   <input type='submit' value='Отправить html5'>"
        , "  </p>"
        , "</form>"
        , "<br>"
        , "<a href='", contextPath, "/log_"+logKey+"'>Диагностика последнего вызова openhtmltopdf</a>"
        , "</body>"
        , "</html>"));
    }

    private void getLog(HttpServletRequest req, HttpServletResponse resp) 
            throws IOException {

        resp.setHeader("Content-Type", "text/html; charset=UTF-8");
        PrintWriter out = resp.getWriter();
        out.print(String.join("\n"
        , "<head>"
        , "<title>Диагностика openhtmltopdf</title>"
        , "</head>"
        , "<body>"
        , "<H2>Диагностика openhtmltopdf</H2>"));

        String key = req.getServletPath().substring(5);
        List<Diagnostic> l = lastLog.get(key);
        lastLog.remove(key);
        out.print("<p>Ключ лога - "+key+"</p>");
        if (l == null) {
            out.print("<p>Логирование выключено</p>");
        } else {
            for (Diagnostic diag : l) {
                if (diag.getLevel() != Level.FINEST && diag.getLevel() != Level.FINER) {
                    out.print(diag.getLevel());
                    out.print(" ");
                    out.print(diag.getFormattedMessage());
                    out.print("<br>");
                }
            }
        }
        out.print(String.join("\n"
        , "</body>"
        , "</html>"));
    }


    /**
    * getServletInfo<BR>
    * Required by Servlet interface
    */
    public String getServletInfo() {
        return "PDF Converter";
    }
}
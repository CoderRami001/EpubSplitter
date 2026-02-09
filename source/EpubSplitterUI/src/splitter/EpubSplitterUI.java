package splitter;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import java.awt.GridLayout;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import java.nio.charset.StandardCharsets;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.UUID;

public class EpubSplitterUI {

	public static void main(String[] args) {
	    try {
	        SwingUtilities.invokeLater(() -> {
	            try {
	                System.out.println("UI START");
	                EpubSplitterUI.ui();
	            } catch (Exception e) {
	                e.printStackTrace();
	            }
	        });
	    } catch (Exception e) {
	        e.printStackTrace();
	    }
	}


    /* ================= UI ================= */

    private static void ui() {    	
        JFrame f = new JFrame("EPUB Section Splitter");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setSize(500, 250);
        f.setVisible(true);

        JTextField titleField = new JTextField();
        JButton srcBtn = new JButton("원본 EPUB 선택");
        JButton outBtn = new JButton("출력 폴더 선택");
        JButton runBtn = new JButton("분리 시작");

        JLabel srcLabel = new JLabel("선택 안 됨");
        JLabel outLabel = new JLabel("선택 안 됨");

        final File[] src = new File[1];
        final File[] out = new File[1];

       srcBtn.setEnabled(false);
       titleField.setEnabled(false);
       
        srcBtn.addActionListener(e -> {
            JFileChooser c = new JFileChooser();
            if (c.showOpenDialog(f) == JFileChooser.APPROVE_OPTION) {
                src[0] = c.getSelectedFile();
                srcLabel.setText(src[0].getName());
            }
            titleField.setEnabled(true);
        });

        outBtn.addActionListener(e -> {
        	System.out.println("OUTPUT!!!");
            JFileChooser c = new JFileChooser();
            c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (c.showOpenDialog(f) == JFileChooser.APPROVE_OPTION) {
                out[0] = c.getSelectedFile();
                outLabel.setText(out[0].getAbsolutePath());
            }
            
            srcBtn.setEnabled(true);
        });

        runBtn.addActionListener(e -> {
            try {
                split(src[0], out[0], titleField.getText().trim());
                JOptionPane.showMessageDialog(f, "완료");
            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(f, ex.getMessage());
            }
        });

        f.setLayout(new GridLayout(6, 1));
        f.add(new JLabel("출력 제목"));
        f.add(titleField);
        f.add(srcBtn); f.add(srcLabel);
        f.add(outBtn); f.add(outLabel);
        f.add(runBtn);

        f.setVisible(true);
    }

    /* ================= CORE ================= */

    private static void split(File epub, File outDir, String baseTitle) throws Exception {

        Map<String, byte[]> files = unzip(epub);
        String metadata = extractMetadata(files.get("OEBPS/content.opf"));

        List<String> sections = files.keySet().stream()
        	    .filter(p ->
        	        p.startsWith("OEBPS/Text/Section") &&
        	        p.endsWith(".xhtml")
        	    )
                .sorted((a, b) -> {
                    if (a.endsWith("cover.xhtml")) return -1;
                    if (b.endsWith("cover.xhtml")) return 1;
                    return a.compareTo(b);
                })
                .toList();


        for (String sec : sections) {
        	int sectionNo = extractSectionNumber(sec);
            String name = baseTitle + " " + sectionNo  + "화";
            File out = new File(outDir, name + ".epub");
            buildSingle(files, sec, metadata, out, sectionNo);
        }
    }

    /* ================= BUILD ================= */

    private static void buildSingle(
            Map<String, byte[]> all,
            String section,
            String metadata,
            File out,
            int idx
    ) throws Exception {

    	Map<String, byte[]> copy = new LinkedHashMap<>();

    	// 1. cover 먼저
    	if (all.containsKey("OEBPS/Text/cover.xhtml")) {
    	    copy.put("OEBPS/Text/cover.xhtml",
    	             all.get("OEBPS/Text/cover.xhtml"));
    	}

    	// 2. 현재 화 section
    	copy.put(section, all.get(section));

    	// 3. 나머지 리소스
    	for (String p : all.keySet()) {
    	    if (
    	        p.startsWith("OEBPS/Styles/") ||
    	        p.startsWith("OEBPS/Images/") ||
    	        p.startsWith("OEBPS/Fonts/") ||
    	        p.startsWith("OEBPS/Audio/") ||
    	        p.startsWith("OEBPS/Video/") ||
    	        p.startsWith("OEBPS/Misc/")
    	    ) {
    	        copy.put(p, all.get(p));
    	    }
    	}


    	byte[] originalToc = all.get("OEBPS/toc.ncx");
    	String toc = buildToc(
    		    originalToc,
    		    section,   // "OEBPS/Text/Section0001.xhtml"
    		    idx
    		);
        String opf = buildOpf(metadata, section, copy.keySet());

        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out))) {

            store(z, "mimetype", "application/epub+zip".getBytes());
            def(z, "META-INF/container.xml",
                    container().getBytes(StandardCharsets.UTF_8));


            for (String p : copy.keySet()) {
                def(z, p, copy.get(p));
            }

            def(z, "OEBPS/toc.ncx", toc.getBytes(StandardCharsets.UTF_8));
            def(z, "OEBPS/content.opf", opf.getBytes(StandardCharsets.UTF_8));
        }
    }
    
    private static int extractSectionNumber(String sectionPath) {
        // 예: OEBPS/Text/Section00012.xhtml → 12
        Matcher m = Pattern.compile("Section0*(\\d+)\\.xhtml").matcher(sectionPath);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        throw new IllegalArgumentException("Invalid section name: " + sectionPath);
    }

    /* ================= OPF / TOC ================= */

    private static String buildOpf(String metadata, String sec, Set<String> files) {

        String secName = sec.substring(sec.lastIndexOf('/') + 1);
        String secId = "Text_" + secName;

        StringBuilder m = new StringBuilder();
        m.append("    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n");

        for (String p : files) {
            String href = p.substring("OEBPS/".length());
            m.append("    <item id=\"")
             .append(href.replace("/", "_"))
             .append("\" href=\"")
             .append(href)
             .append("\" media-type=\"")
             .append(mime(href))
             .append("\"/>\n");
        }

        return
    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
    "<package version=\"2.0\" unique-identifier=\"BookId\"\n" +
    " xmlns=\"http://www.idpf.org/2007/opf\">\n" +
    metadata + "\n" +
    "<manifest>\n" +
    m +
    "</manifest>\n" +
    "<spine toc=\"ncx\">\n" +
    "  <itemref idref=\"Text_cover.xhtml\"/>\n" +
    "  <itemref idref=\"" + secId + "\"/>\n" +
    "</spine>\n"
    + "</package>\n"
    + "";
    }


    private static String buildToc(byte[] originalTocBytes,
            String sectionPath,
            int idx) {

		String toc = new String(originalTocBytes, StandardCharsets.UTF_8);
		String sectionName = sectionPath.substring(sectionPath.lastIndexOf('/') + 1);
		String title = idx + "화";
		
		// 1. docTitle 변경
		toc = toc.replaceAll(
			"(?s)(<docTitle>\\s*<text>).*?(</text>\\s*</docTitle>)",
			"$1" + title + "$2"
		);
		
		// 2. navPoint 전체 추출
		Matcher m = Pattern.compile("(?s)<navPoint.*?</navPoint>").matcher(toc);
		
		StringBuilder kept = new StringBuilder();
		int playOrder = 1;
		
		while (m.find()) {
			String navPoint = m.group();
		
			boolean keep =
		            navPoint.contains("Text/cover.xhtml") ||
		            navPoint.contains("Text/" + sectionName);
			
			if(!keep) continue;
			
	        if (navPoint.contains("Text/" + sectionName)) {
	            navPoint = navPoint.replaceAll(
	                "(?s)(<navLabel>\\s*<text>).*?(</text>\\s*</navLabel>)",
	                "$1" + title + "$2"
	            );
	        }
	        
	        // id / playOrder 재설정
	        navPoint = navPoint
	            .replaceAll("id=\"navPoint-\\d+\"", "id=\"navPoint-" + playOrder + "\"")
	            .replaceAll("playOrder=\"\\d+\"", "playOrder=\"" + playOrder + "\"");

	        kept.append(navPoint).append("\n");
	        playOrder++;
	    }
	        

		// 3. navMap 교체
		toc = toc.replaceAll(
			"(?s)<navMap>.*?</navMap>",
			"<navMap>\n" + kept + "\n</navMap>"
		);
		
		return toc;
	}



    /* ================= UTIL ================= */

    private static Map<String, byte[]> unzip(File f) throws Exception {
        Map<String, byte[]> m = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(f))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                m.put(e.getName(), z.readAllBytes());
            }
        }
        return m;
    }

    private static String extractMetadata(byte[] opf) {
        String s = new String(opf, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("(?s)<metadata.*?</metadata>").matcher(s);
        return m.find() ? m.group() : "<metadata/>";
    }

    private static String container() {
        return """
<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0"
 xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf"
     media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
""";
    }

    private static void store(ZipOutputStream z, String n, byte[] d) throws Exception {
        ZipEntry e = new ZipEntry(n);
        e.setMethod(ZipEntry.STORED);
        e.setSize(d.length);
        CRC32 c = new CRC32(); c.update(d);
        e.setCrc(c.getValue());
        z.putNextEntry(e);
        z.write(d);
        z.closeEntry();
    }

    private static void def(ZipOutputStream z, String n, byte[] d) throws Exception {
        z.putNextEntry(new ZipEntry(n));
        z.write(d);
        z.closeEntry();
    }

    private static String mime(String f) {
        f = f.toLowerCase();
        if (f.endsWith(".xhtml")) return "application/xhtml+xml";
        if (f.endsWith(".css")) return "text/css";
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".mp3")) return "audio/mpeg";
        if (f.endsWith(".mp4")) return "video/mp4";
        if (f.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }
}

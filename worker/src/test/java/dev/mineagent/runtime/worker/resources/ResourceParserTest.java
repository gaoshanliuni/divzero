package dev.mineagent.runtime.worker.resources;
import dev.mineagent.runtime.core.resources.ResourceLibrary;import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import java.util.*;import java.awt.Color;import java.awt.image.BufferedImage;import java.io.*;import static org.junit.jupiter.api.Assertions.*;
class ResourceParserTest {
 @TempDir Path root;private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
 private ResourceLibrary library()throws Exception{return new ResourceLibrary(root,new UUID(1,2),new UUID(3,4));}
 @Test void imageCropUsesOriginalPixelsAndKeepsBatchImages()throws Exception{
  var image=new BufferedImage(800,200,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(Color.RED);g.fillRect(0,0,400,200);g.setColor(Color.BLUE);g.fillRect(400,0,400,200);g.dispose();var bytes=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
  var selected=ResourceImages.prepare(bytes.toByteArray(),List.of(400,0,400,200),"high");assertEquals(800,selected.sourceWidth());assertEquals(400,selected.selection().x());assertEquals(Color.BLUE.getRGB(),javax.imageio.ImageIO.read(new ByteArrayInputStream(selected.image().bytes())).getRGB(100,100));
  assertThrows(IllegalArgumentException.class,()->ResourceImages.prepare(bytes.toByteArray(),List.of(799,0,2,5),"high"));var second=ResourceImages.prepare(bytes.toByteArray(),List.of(0,0,400,200),"high");dev.mineagent.runtime.api.model.ModelImage.validateBatch(List.of(selected.image(),second.image()));
 }
 @Test void parsesTsvAndMergedHtmlTablesWithoutExecutingContent()throws Exception{
  var l=library();var tsv=l.save("data.tsv","text/tab-separated-values","test", "name\tcount\nstone\t12\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),null,Map.of());var result=ResourceParser.read(l,tsv.id(),JSON.createObjectNode());assertEquals("tsv",result.get("detectedType"));assertTrue(result.get("cells").toString().contains("12"));
  var table=org.jsoup.Jsoup.parse("<table><tr><th rowspan='2'>A</th><th colspan='2'>B</th></tr><tr><td>C</td><td>D</td></tr></table>").selectFirst("table");assertEquals(List.of(List.of("A","B","B"),List.of("A","C","D")),ResourceTables.html(table));
 }
 @Test void readsPdfPageTextCoordinatesAndRealRenderedImage()throws Exception{
  byte[] pdf;try(var doc=new org.apache.pdfbox.pdmodel.PDDocument()){var page=new org.apache.pdfbox.pdmodel.PDPage();doc.addPage(page);try(var text=new org.apache.pdfbox.pdmodel.PDPageContentStream(doc,page)){text.beginText();text.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);text.newLineAtOffset(40,700);text.showText("Item");text.newLineAtOffset(160,0);text.showText("Count");text.endText();}var out=new ByteArrayOutputStream();doc.save(out);pdf=out.toByteArray();}
  var l=library();var resource=l.save("table.pdf","application/pdf","fixture",pdf,null,Map.of());var parsed=ResourceParser.read(l,resource.id(),JSON.createObjectNode().put("page",1).put("mode","table").put("render",true));var json=JSON.valueToTree(parsed);assertEquals("POSITION_INFERRED",json.at("/pages/0/tableMethod").asText());assertTrue(json.at("/pages/0/text").asText().contains("Count"));UUID image=UUID.fromString(json.at("/pages/0/image/resource_id").asText());assertEquals(resource.id(),l.get(image).parent());assertTrue(ResourceImages.prepare(l.read(image),List.of(),"low").image().width()>0);
 }
 @Test void spreadsheetDoesNotEvaluateFormulasAndAudioReturnsSelectedPcm()throws Exception{
  byte[] spreadsheet;try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook()){var row=book.createSheet("Sheet").createRow(0);row.createCell(0).setCellValue("stone");row.createCell(1).setCellFormula("1+2");var out=new ByteArrayOutputStream();book.write(out);spreadsheet=out.toByteArray();}
  var l=library();var file=l.save("cells.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","fixture",spreadsheet,null,Map.of());var parsed=ResourceParser.read(l,file.id(),JSON.createObjectNode().put("column_count",2));assertEquals(false,parsed.get("formulaExecution"));assertTrue(parsed.get("cells").toString().contains("1+2"));
  byte[] pcm=new byte[3*32000];Arrays.fill(pcm,32000,64000,(byte)2);Path wav=root.resolve("clip.wav");Files.write(wav,dev.mineagent.runtime.core.conversation.SpeechWav.encode(pcm));byte[] selected=ResourceAudio.wav(root,wav,1,1);dev.mineagent.runtime.core.conversation.SpeechWav.validate(selected);assertEquals(32044,selected.length);assertEquals(2,selected[44]);
 }
}

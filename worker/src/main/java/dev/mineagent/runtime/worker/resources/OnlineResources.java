package dev.mineagent.runtime.worker.resources;
import dev.mineagent.runtime.core.resources.ResourceLibrary;import dev.mineagent.runtime.worker.web.*;import java.util.*;import java.nio.charset.StandardCharsets;import java.io.*;

public final class OnlineResources {
 public static ResourceLibrary.Resource fetch(ResourceLibrary library,String url,boolean dynamic)throws Exception{
  var page=new PublicHttpsReader((int)ResourceLibrary.MAX_BYTES).get(url);if(page.status()!=200)throw new IOException("RESOURCE_HTTP_"+page.status());String mime=page.contentType().split(";",2)[0].strip();byte[] bytes=page.body();var selection=new LinkedHashMap<String,String>();selection.put("network","JAVA_PUBLIC_HTTPS");
  if(dynamic){if(!mime.contains("html"))throw new IllegalArgumentException("DYNAMIC_REQUIRES_HTML");bytes=dynamic(page);selection.put("parser","HTMLUNIT_RENDERED_DOM");selection.put("scope","DOM after bounded JavaScript rendering; not a full browser screenshot");}
  String path=page.url().getPath(),name=path.substring(path.lastIndexOf('/')+1);if(name.isBlank())name="page.html";if(name.length()>180)name=name.substring(name.length()-180);return library.save(name,mime,page.url().toString(),bytes,null,selection);
 }
 public static Map<String,Object> search(String query,String kind)throws Exception{
  if(!Set.of("all","web","image","pdf","video","audio","table").contains(kind))throw new IllegalArgumentException("RESOURCE_SEARCH_KIND");if(kind.equals("image"))return ImageSearchService.search(query);
  String suffix=switch(kind){case "pdf"->" filetype:pdf";case "table"->" (filetype:csv OR filetype:xlsx)";case "video"->" video";case "audio"->" audio";default->"";};var result=new LinkedHashMap<String,Object>(new WebResearchService().search(query+suffix,"web"));result.put("resourceKind",kind);result.put("readTool","read_resource");result.put("imageTool","inspect_image");return result;
 }
 private static byte[] dynamic(PublicHttpsReader.Page initial)throws Exception{
  try(var client=new org.htmlunit.WebClient(org.htmlunit.BrowserVersion.CHROME)){
   client.getOptions().setCssEnabled(false);client.getOptions().setJavaScriptEnabled(true);client.getOptions().setThrowExceptionOnScriptError(false);client.getOptions().setThrowExceptionOnFailingStatusCode(false);client.getOptions().setDownloadImages(false);client.getOptions().setTimeout(10000);client.setJavaScriptTimeout(3000);
   long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);var count=new java.util.concurrent.atomic.AtomicInteger();
   client.setWebConnection(new org.htmlunit.WebConnection(){public org.htmlunit.WebResponse getResponse(org.htmlunit.WebRequest request)throws IOException{if(count.incrementAndGet()>32||System.nanoTime()>deadline)throw new IOException("DYNAMIC_PAGE_REQUEST_BUDGET");if(request.getHttpMethod()!=org.htmlunit.HttpMethod.GET)throw new IOException("DYNAMIC_PAGE_READ_ONLY");try{var page=request.getUrl().toString().equals(initial.url().toString())?initial:new PublicHttpsReader(4*1024*1024).get(request.getUrl().toString());var headers=List.of(new org.htmlunit.util.NameValuePair("Content-Type",page.contentType()));return new org.htmlunit.WebResponse(new org.htmlunit.WebResponseData(page.body(),page.status(),"",headers),request,0);}catch(IOException e){throw e;}catch(Exception e){throw new IOException("DYNAMIC_RESOURCE_REJECTED",e);}}public void close(){}});
   var page=client.getPage(initial.url().toString());if(!(page instanceof org.htmlunit.html.HtmlPage html))throw new IOException("DYNAMIC_PAGE_NOT_HTML");client.waitForBackgroundJavaScript(1200);byte[] bytes=html.asXml().getBytes(StandardCharsets.UTF_8);if(bytes.length>8*1024*1024)throw new IOException("DYNAMIC_DOM_TOO_LARGE");return bytes;
  }
 }
 private OnlineResources(){}
}

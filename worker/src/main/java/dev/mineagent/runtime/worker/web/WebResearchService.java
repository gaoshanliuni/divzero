package dev.mineagent.runtime.worker.web;

import org.jsoup.Jsoup;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Live search snippets and separately fetched source pages. No cached fixture fallback. */
public final class WebResearchService {
    private final PublicHttpsReader reader=new PublicHttpsReader();
    private static volatile long webUnavailableUntil;
    static void requireSearchAvailable(String scope){if(scope.equals("web")&&System.nanoTime()<webUnavailableUntil)throw new IllegalStateException("WEB_SEARCH_TEMPORARILY_UNAVAILABLE");}
    static void markSearchUnavailable(){webUnavailableUntil=System.nanoTime()+java.time.Duration.ofMinutes(5).toNanos();}
    public Map<String,Object> search(String query)throws Exception{return search(query,"minecraft");}
    public Map<String,Object> search(String query,String scope)throws Exception{
        if(!Set.of("minecraft","web").contains(scope))throw new IllegalArgumentException("WEB_SEARCH_SCOPE");
        if(query==null||query.isBlank()||query.length()>256||query.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("WEB_QUERY_INVALID");
        requireSearchAvailable(scope);
        String url=(scope.equals("minecraft")?"https://search.mcmod.cn/s?key=":"https://html.duckduckgo.com/html/?q=")+URLEncoder.encode(query,StandardCharsets.UTF_8);var page=reader.get(url);if(scope.equals("web")&&Set.of(202,403,429).contains(page.status()))markSearchUnavailable();requireOk(page);var result=scope.equals("minecraft")?parseMinecraftSearch(new String(page.body(),StandardCharsets.UTF_8)):parseSearch(new String(page.body(),StandardCharsets.UTF_8));
        return Map.of("status","OBSERVED","kind","SEARCH_SNIPPETS_NOT_FULL_PAGES","query",query,"provider",scope.equals("minecraft")?"MC百科站内搜索":"DuckDuckGo HTML","results",result,"fetchedAt",page.fetchedAt(),"sha256",hash(page.body()),"untrustedContent",true);
    }
    public Map<String,Object> read(String url)throws Exception{
        var page=reader.get(url);requireOk(page);String mime=page.contentType().toLowerCase(Locale.ROOT);if(!(mime.contains("text/html")||mime.contains("application/xhtml")||mime.contains("text/plain")))throw new IllegalArgumentException("WEB_PAGE_TYPE_UNSUPPORTED");
        var document=Jsoup.parse(new java.io.ByteArrayInputStream(page.body()),null,page.url().toString());String title=clip(document.title(),200);document.select("script,style,noscript,nav,header,footer,iframe,form,svg").remove();var main=document.selectFirst("#mw-content-text,.item-content,article,main");String text=(main==null?document.body():main).text();
        if(text.length()<80)throw new IllegalArgumentException("WEB_PAGE_NO_READABLE_TEXT");
        return Map.of("status","OBSERVED","kind","FETCHED_PAGE_TEXT","url",page.url().toString(),"title",title,"text",clip(text,12000),"truncated",text.length()>12000,"fetchedAt",page.fetchedAt(),"sha256",hash(page.body()),"untrustedContent",true);
    }
    static List<Map<String,Object>> parseMinecraftSearch(String html){
        var document=Jsoup.parse(html,"https://search.mcmod.cn/");var results=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();
        for(var item:document.select(".result-item")){
            var link=item.select(".head a[href]").stream().filter(a->!a.text().isBlank()).reduce((a,b)->b).orElse(null);if(link==null)continue;String url;
            try{url=PublicHttpsReader.address(URI.create("https://search.mcmod.cn/").resolve(link.attr("href")).toString()).toString();}catch(Exception bad){continue;}
            if(!seen.add(url))continue;var body=item.selectFirst(".body");results.add(Map.of("title",clip(link.text(),200),"url",url,"snippet",body==null?"":clip(body.text(),900)));if(results.size()==6)break;
        }
        return List.copyOf(results);
    }
    static List<Map<String,Object>> parseSearch(String html){
        var document=Jsoup.parse(html,"https://html.duckduckgo.com/");var results=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();
        for(var result:document.select(".result")){
            var link=result.selectFirst("a.result__a");if(link==null)continue;String url=link.attr("href");
            try{URI uri=URI.create("https://html.duckduckgo.com/").resolve(url);url=uri.toString();if(Set.of("duckduckgo.com","html.duckduckgo.com").contains(uri.getHost())&&uri.getRawQuery()!=null){for(String part:uri.getRawQuery().split("&"))if(part.startsWith("uddg=")){url=URLDecoder.decode(part.substring(5),StandardCharsets.UTF_8);break;}}url=PublicHttpsReader.address(url).toString();}catch(Exception invalid){continue;}
            if(!seen.add(url))continue;var snippet=result.selectFirst(".result__snippet");results.add(Map.of("title",clip(link.text(),200),"url",url,"snippet",snippet==null?"":clip(snippet.text(),900)));if(results.size()==6)break;
        }
        if(results.isEmpty()&&(!document.select("#challenge-form,#anomaly-modal").isEmpty()||document.text().contains("Unfortunately, bots use DuckDuckGo too"))){markSearchUnavailable();throw new IllegalArgumentException("WEB_SEARCH_CHALLENGE");}return List.copyOf(results);
    }
    private static void requireOk(PublicHttpsReader.Page page){if(page.status()!=200)throw new IllegalArgumentException("WEB_HTTP_"+page.status());}
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String clip(String text,int max){return text.length()<=max?text:text.substring(0,max);}
}

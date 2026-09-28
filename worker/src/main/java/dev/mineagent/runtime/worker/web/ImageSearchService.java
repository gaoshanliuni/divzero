package dev.mineagent.runtime.worker.web;

import com.fasterxml.jackson.databind.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Public image discovery with explicit source pages; search text is never treated as a download instruction. */
public final class ImageSearchService {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static JsonNode read(String url)throws Exception{var page=new PublicHttpsReader().get(url);if(page.status()!=200)throw new IllegalStateException("IMAGE_SEARCH_HTTP_"+page.status());return JSON.readTree(page.body());}
    public static Map<String,Object> search(String query)throws Exception{
        if(query==null||query.isBlank()||query.length()>160)throw new IllegalArgumentException("IMAGE_SEARCH_QUERY");
        var results=new ArrayList<Map<String,Object>>();var errors=new ArrayList<String>();String language=query.codePoints().anyMatch(c->c>127)?"zh":"en";
        try{
            var root=read("https://"+language+".wikipedia.org/w/api.php?action=query&format=json&redirects=1&titles="+encode(query)+"&prop=pageimages&piprop=original%7Cname&pilicense=any");
            for(var page:root.path("query").path("pages")){var image=page.path("original");String url=image.path("source").asText("");if(!url.isBlank()){
                PublicHttpsReader.address(url);String name=page.path("pageimage").asText(),source="https://"+language+".wikipedia.org/wiki/File:"+encode(name);
                results.add(Map.of("title",page.path("title").asText(),"filename",name,"url",url,"sourcePage",source,"width",image.path("width").asInt(),"height",image.path("height").asInt(),"kind","ARTICLE_PRIMARY_IMAGE","license","See source page; may be copyrighted"));
            }}
        }catch(Exception failure){errors.add("ARTICLE_IMAGE_UNAVAILABLE");}
        try{
            var root=read("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=10&gsrsearch="+encode(query)+"&prop=imageinfo&iiprop=url%7Csize%7Cextmetadata&iiurlwidth=512");
            for(var page:root.path("query").path("pages")){var info=page.path("imageinfo").path(0);String url=info.path("thumburl").asText(info.path("url").asText(""));if(url.isBlank()||!url.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|webp)(?:\\?.*)?"))continue;
                PublicHttpsReader.address(url);results.add(Map.of("title",page.path("title").asText(),"url",url,"originalUrl",info.path("url").asText(),"sourcePage",info.path("descriptionurl").asText(),"width",info.path("thumbwidth").asInt(info.path("width").asInt()),"height",info.path("thumbheight").asInt(info.path("height").asInt()),"kind","COMMONS_SEARCH_RESULT","license",info.path("extmetadata").path("LicenseShortName").path("value").asText("See source page")));
            }
        }catch(Exception failure){errors.add("COMMONS_SEARCH_UNAVAILABLE");}
        return Map.of("status",results.isEmpty()?"UNAVAILABLE":"OBSERVED","query",query,"images",results,"errors",errors,"untrustedMetadata",true,"note","Check title/filename/source: artwork, promotional images and cosplay photographs are different results. No image has been applied.");
    }
    private ImageSearchService(){}
}

package dev.mineagent.runtime.worker.web;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WebResearchServiceTest {
    @Test void buildingCatalogReturnsActualArticleLinksNotMenus(){var rows=WebResearchService.parseBuildingCatalog("<nav><a href='/'>home</a></nav><article><h2><a href='/medieval-house/'>Medieval House</a></h2><p>Build with a world download.</p></article>");assertEquals(1,rows.size());assertEquals("https://minecraftbuildinginc.com/medieval-house/",rows.getFirst().get("url"));}
    @Test void refusesRepeatedBlockedProviderCallsButKeepsMinecraftSearchAvailable(){WebResearchService.markSearchUnavailable();assertThrows(IllegalStateException.class,()->WebResearchService.requireSearchAvailable("web"));assertDoesNotThrow(()->WebResearchService.requireSearchAvailable("minecraft"));}
    @Test void minecraftResultsSelectTheNamedEntryInsteadOfItsCategoryIcon(){var rows=WebResearchService.parseMinecraftSearch("<div class='result-item'><div class='head'><a href='https://www.mcmod.cn/class/category/3.html'></a><a href='https://www.mcmod.cn/item/9363.html'>巫妖 (Twilight Lich)</a></div><div class='body'>正文摘要</div></div>");assertEquals(1,rows.size());assertEquals("https://www.mcmod.cn/item/9363.html",rows.getFirst().get("url"));}

    @Test void decodesSearchLinksWithoutExecutingMarkupOrTrustingCredentials(){
        String html="<div class='result'><a class='result__a' href='//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fguide'>Lich <b>guide</b></a><span class='result__snippet'>Reflect projectiles.</span></div><div class='result'><a class='result__a' href='https://x:y@example.com/'>bad</a></div>";
        var rows=WebResearchService.parseSearch(html);assertEquals(1,rows.size());assertEquals("https://example.com/guide",rows.getFirst().get("url"));assertEquals("Lich guide",rows.getFirst().get("title"));assertEquals("Reflect projectiles.",rows.getFirst().get("snippet"));
    }
    @Test void neverConvertsAChallengeIntoFabricatedSearchResults(){assertThrows(IllegalArgumentException.class,()->WebResearchService.parseSearch("<form id='challenge-form'>captcha</form>"));assertTrue(WebResearchService.parseSearch("<p>No results.</p>").isEmpty());}
}

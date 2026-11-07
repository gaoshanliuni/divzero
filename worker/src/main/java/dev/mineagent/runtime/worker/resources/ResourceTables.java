package dev.mineagent.runtime.worker.resources;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.jsoup.nodes.Element;
import java.io.IOException;
import java.util.*;

/** Read-only table adapters. PDF inferred cells retain coordinates and explicitly report uncertainty. */
final class ResourceTables {
    static List<List<String>> html(Element table){
        var grid=new ArrayList<List<String>>();int row=0,occupied=0;
        for(var tr:table.select("tr")){
            if(tr.closest("table")!=table)continue;
            if(row>=10000)throw new IllegalArgumentException("HTML_TABLE_ROW_LIMIT");
            ensure(grid,row);int column=0;
            for(var cell:tr.children()){
                if(!Set.of("td","th").contains(cell.tagName()))continue;
                while(column<grid.get(row).size()&&grid.get(row).get(column)!=null)column++;
                int rs=span(cell,"rowspan"),cs=span(cell,"colspan");if(column+cs>512||row+rs>10000||(occupied+=rs*cs)>100000)throw new IllegalArgumentException("HTML_TABLE_SPAN_LIMIT");
                for(int r=row;r<row+rs;r++){ensure(grid,r);var values=grid.get(r);while(values.size()<column+cs)values.add(null);for(int c=column;c<column+cs;c++){if(values.get(c)!=null)throw new IllegalArgumentException("HTML_TABLE_OVERLAPPING_SPANS");values.set(c,cell.text());}}
                column+=cs;
            }row++;
        }
        return grid.stream().map(values->values.stream().map(s->s==null?"":s).toList()).toList();
    }
    private static void ensure(List<List<String>> grid,int row){while(grid.size()<=row)grid.add(new ArrayList<>());}
    private static int span(Element cell,String field){String text=cell.attr(field);if(text.isBlank())return 1;try{int n=Integer.parseInt(text);if(n<1||n>128)throw new NumberFormatException();return n;}catch(NumberFormatException error){throw new IllegalArgumentException("HTML_TABLE_"+field.toUpperCase(Locale.ROOT)+": expected 1..128");}}
    private record Glyph(String text,float x,float y,float width,float height,float space){}
    private record Cell(String text,float x,float y,float width,float height){}
    static void pdf(PDDocument doc,int page,JsonNode args,Map<String,Object> out)throws IOException{
        var glyphs=new ArrayList<Glyph>();
        var extractor=new PDFTextStripper(){
            @Override protected void writeString(String text,List<TextPosition> positions)throws IOException{
                for(var p:positions){if(glyphs.size()>=200000)throw new IOException("PDF_TABLE_GLYPH_LIMIT");glyphs.add(new Glyph(p.getUnicode(),p.getXDirAdj(),p.getYDirAdj(),p.getWidthDirAdj(),p.getHeightDir(),p.getWidthOfSpace()));}
            }
        };
        extractor.setSortByPosition(true);extractor.setStartPage(page);extractor.setEndPage(page);extractor.getText(doc);
        double[] region=null;
        if(args.has("table_region")){
            var a=args.get("table_region");if(!a.isArray()||a.size()!=4)throw new IllegalArgumentException("RESOURCE_FIELD table_region: expected [x,y,width,height] in PDF points");region=new double[4];
            for(int i=0;i<4;i++){if(!a.get(i).isNumber()||!Double.isFinite(a.get(i).doubleValue())||a.get(i).doubleValue()<0)throw new IllegalArgumentException("RESOURCE_FIELD table_region["+i+"]: expected nonnegative finite number");region[i]=a.get(i).doubleValue();}
            if(region[2]<=0||region[3]<=0)throw new IllegalArgumentException("RESOURCE_FIELD table_region: width and height must be positive");
        }
        final double[] selected=region;
        glyphs.removeIf(g->selected!=null&&(g.x()<selected[0]||g.x()>selected[0]+selected[2]||g.y()<selected[1]||g.y()>selected[1]+selected[3]));
        glyphs.sort(Comparator.comparingDouble(Glyph::y).thenComparingDouble(Glyph::x));
        var lines=new ArrayList<List<Glyph>>();
        for(var glyph:glyphs){if(lines.isEmpty()||Math.abs(lines.getLast().getFirst().y()-glyph.y())>Math.max(2,glyph.height()*.3))lines.add(new ArrayList<>());lines.getLast().add(glyph);}
        var rows=new ArrayList<List<Cell>>();var anchors=new TreeSet<Float>();
        for(var line:lines){line.sort(Comparator.comparingDouble(Glyph::x));var cells=new ArrayList<Cell>();StringBuilder text=new StringBuilder();float x=0,y=0,end=0,height=0;
            for(var glyph:line){float gap= glyph.x()-end;
                if(!text.isEmpty()&&gap>Math.max(6,glyph.space()*2.2)){cells.add(new Cell(text.toString().strip(),x,y,end-x,height));text.setLength(0);}
                if(text.isEmpty()){x=glyph.x();y=glyph.y();height=glyph.height();}else if(gap>Math.max(1,glyph.space()*.4)&&!Character.isWhitespace(text.charAt(text.length()-1)))text.append(' ');
                text.append(glyph.text());end=Math.max(glyph.x()+glyph.width(),end);height=Math.max(height,glyph.height());
            }
            if(!text.isEmpty())cells.add(new Cell(text.toString().strip(),x,y,end-x,height));
            for(var cell:cells){Float floor=anchors.floor(cell.x()+3);if(floor==null||Math.abs(floor-cell.x())>3)anchors.add(cell.x());}
            rows.add(cells);
        }
        var columns=new ArrayList<>(anchors);int first=ResourceParser.integer(args,"row_start",0,0,rows.size()),count=ResourceParser.integer(args,"row_count",32,1,128),column=ResourceParser.integer(args,"column_start",0,0,16383),width=ResourceParser.integer(args,"column_count",16,1,64);var cells=new ArrayList<Object>();
        for(int r=first;r<Math.min(rows.size(),first+count);r++)for(var cell:rows.get(r)){int c=0;for(int i=1;i<columns.size();i++)if(Math.abs(columns.get(i)-cell.x())<Math.abs(columns.get(c)-cell.x()))c=i;if(c>=column&&c<column+width)cells.add(Map.of("row",r,"column",c,"text",cell.text(),"bounds",List.of(cell.x(),cell.y()-cell.height(),cell.width(),cell.height())));}
        out.put("cells",cells);out.put("totalRows",rows.size());out.put("columnAnchors",columns);out.put("nextRow",first+count<rows.size()?first+count:-1);out.put("tableMethod","POSITION_INFERRED");out.put("coordinateSystem","PDF points, top-left origin");out.put("tableWarning",glyphs.isEmpty()?"No extractable text; render the page and inspect the actual pixels.":"Columns inferred from text spacing; verify merged or wrapped cells against the rendered page.");
    }
    private ResourceTables(){}
}

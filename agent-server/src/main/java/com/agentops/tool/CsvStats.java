package com.agentops.tool;
import java.math.*;
import java.util.*;

public final class CsvStats {
    private CsvStats() {}
    public static Map<String,Object> analyze(String csv,String column) {
        if(csv==null || csv.length()>24000)throw new IllegalArgumentException("CSV不能超过24000字符");
        var rows=parse(csv);if(rows.size()<2)throw new IllegalArgumentException("CSV须包含表头和数据行");
        var header=rows.get(0);int index=header.indexOf(column);
        if(index<0 || header.lastIndexOf(column)!=index)throw new IllegalArgumentException("数值列不存在或表头重名");
        int missing=0,invalid=0,count=0;BigDecimal sum=BigDecimal.ZERO,min=null,max=null;
        for(var row:rows.subList(1,rows.size())) {
            if(row.size()!=header.size())throw new IllegalArgumentException("CSV各行列数必须一致");
            String value=row.get(index).strip();if(value.isEmpty()){missing++;continue;}
            try {
                if(value.length()>128)throw new NumberFormatException();
                if(!value.matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)") && !value.matches("[+-]?\\d{1,3}(?:,\\d{3})+(?:\\.\\d*)?"))throw new NumberFormatException();
                var n=new BigDecimal(value.replace(",",""),MathContext.DECIMAL128);
                sum=sum.add(n,MathContext.DECIMAL128);min=min==null?n:min.min(n);max=max==null?n:max.max(n);count++;
            } catch(NumberFormatException e){invalid++;}
        }
        if(count==0)throw new IllegalArgumentException("指定列没有有效数值");
        return Map.of("column",column,"rows",rows.size()-1,"valid",count,"missing",missing,"invalid",invalid,
                "sum",sum.stripTrailingZeros().toPlainString(),"average",sum.divide(BigDecimal.valueOf(count),MathContext.DECIMAL128).stripTrailingZeros().toPlainString(),
                "min",min.stripTrailingZeros().toPlainString(),"max",max.stripTrailingZeros().toPlainString());
    }
    static List<List<String>> parse(String input) {
        String csv=input.startsWith("\ufeff")?input.substring(1):input;
        var rows=new ArrayList<List<String>>();var row=new ArrayList<String>();var cell=new StringBuilder();boolean quoted=false,closed=false;
        for(int i=0;i<csv.length();i++) {
            char c=csv.charAt(i);
            if(quoted) {
                if(c=='"') {if(i+1<csv.length() && csv.charAt(i+1)=='"'){cell.append('"');i++;}else{quoted=false;closed=true;}}
                else cell.append(c);
            } else if(c=='"') {
                if(!cell.isEmpty() || closed)throw new IllegalArgumentException("CSV引号位置无效");quoted=true;
            } else if(c==',' || c=='\n' || c=='\r') {
                row.add(cell.toString());cell.setLength(0);closed=false;
                if(c!=',') {if(c=='\r'&&i+1<csv.length()&&csv.charAt(i+1)=='\n')i++;rows.add(List.copyOf(row));row.clear();}
            } else {if(closed && !Character.isWhitespace(c))throw new IllegalArgumentException("CSV闭合引号后有无效字符");if(!closed)cell.append(c);}
            if(row.size()>100 || rows.size()>5000)throw new IllegalArgumentException("CSV行列过多");
        }
        if(quoted)throw new IllegalArgumentException("CSV引号未闭合");
        if(!cell.isEmpty() || !row.isEmpty() || closed){row.add(cell.toString());rows.add(List.copyOf(row));}
        return rows;
    }
}

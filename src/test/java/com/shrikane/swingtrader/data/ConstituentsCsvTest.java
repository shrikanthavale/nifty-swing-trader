package com.shrikane.swingtrader.data;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstituentsCsvTest {

    private static final String SAMPLE = """
            Company Name,Industry,Symbol,Series,ISIN Code
            Reliance Industries Ltd.,Oil Gas & Consumable Fuels,RELIANCE,EQ,INE002A01018
            "Larsen & Toubro Ltd.",Construction,LT,EQ,INE018A01030
            "Tata Consultancy Services Ltd.",Information Technology,TCS,EQ,INE467B01029
            """;

    @Test
    void parsesSymbolsFromNseFormat() {
        Set<String> symbols = ConstituentsCsv.parseSymbols(SAMPLE);
        assertEquals(Set.of("RELIANCE", "LT", "TCS"), symbols);
    }

    @Test
    void quotedCompanyNameWithCommaDoesNotShiftColumns() {
        String csv = """
                Company Name,Industry,Symbol,Series,ISIN Code
                "Sun Pharmaceutical Industries Ltd., of India",Healthcare,SUNPHARMA,EQ,INE044A01036
                """;
        assertEquals(Set.of("SUNPHARMA"), ConstituentsCsv.parseSymbols(csv));
    }

    @Test
    void nonEqSeriesRowsAreSkipped() {
        String csv = """
                Company Name,Industry,Symbol,Series,ISIN Code
                Some Trust,Financial Services,SOMETRUST,BE,INE000000001
                Reliance Industries Ltd.,Energy,RELIANCE,EQ,INE002A01018
                """;
        assertEquals(Set.of("RELIANCE"), ConstituentsCsv.parseSymbols(csv));
    }

    @Test
    void rejectsHtmlErrorPage() {
        assertThrows(IllegalArgumentException.class, () ->
                ConstituentsCsv.parseSymbols("<html><body>Access Denied</body></html>"));
    }

    @Test
    void rejectsEmptyInput() {
        assertThrows(IllegalArgumentException.class, () -> ConstituentsCsv.parseSymbols(""));
        assertThrows(IllegalArgumentException.class, () -> ConstituentsCsv.parseSymbols(null));
    }

    @Test
    void rejectsHeaderOnlyCsv() {
        assertThrows(IllegalArgumentException.class, () ->
                ConstituentsCsv.parseSymbols("Company Name,Industry,Symbol,Series,ISIN Code\n"));
    }

    @Test
    void lineParserHandlesQuotesAndEscapes() {
        assertEquals(List.of("a", "b,c", "d\"e", ""),
                ConstituentsCsv.parseLine("a,\"b,c\",\"d\"\"e\","));
    }

    @Test
    void duplicateSymbolsCollapse() {
        String csv = """
                Company Name,Industry,Symbol,Series,ISIN Code
                Reliance Industries Ltd.,Energy,RELIANCE,EQ,INE002A01018
                Reliance Industries Ltd.,Energy,RELIANCE,EQ,INE002A01018
                """;
        assertTrue(ConstituentsCsv.parseSymbols(csv).size() == 1);
    }
}

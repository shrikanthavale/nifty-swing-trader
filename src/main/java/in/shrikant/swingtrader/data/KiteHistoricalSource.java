package in.shrikant.swingtrader.data;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Kite Connect implementation of {@link HistoricalSource}.
 *
 * Note: the historical data API needs the paid Connect plan — on a free
 * Personal app these calls fail with a permission error. The error message
 * from Kite says so explicitly; nothing to fix in code.
 */
public final class KiteHistoricalSource implements HistoricalSource {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final KiteConnect kite;

    public KiteHistoricalSource(KiteConnect kite) {
        this.kite = kite;
    }

    @Override
    public List<Candle> fetchDaily(String symbol, long instrumentToken,
                                   LocalDate from, LocalDate to) throws IOException {
        Date fromDate = Date.from(from.atStartOfDay(IST).toInstant());
        Date toDate = Date.from(to.atTime(23, 59).atZone(IST).toInstant());
        HistoricalData data;
        try {
            data = kite.getHistoricalData(fromDate, toDate,
                    String.valueOf(instrumentToken), "day", false, false);
        } catch (KiteException e) {
            throw new IOException("Kite historical fetch failed for " + symbol
                    + ": " + e.message + " (code " + e.code + ")", e);
        }
        List<Candle> candles = new ArrayList<>();
        if (data != null && data.dataArrayList != null) {
            for (HistoricalData bar : data.dataArrayList) {
                candles.add(new Candle(symbol, parseDate(bar.timeStamp),
                        bar.open, bar.high, bar.low, bar.close, bar.volume));
            }
        }
        return candles;
    }

    /**
     * Kite timestamps look like "2017-12-15T09:15:00+0530" — the "+0530"
     * (no colon) breaks ISO parsers, and for daily bars only the date part
     * matters anyway. Pure, unit-tested.
     */
    static LocalDate parseDate(String kiteTimestamp) {
        if (kiteTimestamp == null || kiteTimestamp.length() < 10) {
            throw new IllegalArgumentException("Unparseable Kite timestamp: " + kiteTimestamp);
        }
        return LocalDate.parse(kiteTimestamp.substring(0, 10));
    }
}

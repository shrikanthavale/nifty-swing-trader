package in.shrikant.swingtrader.data;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import in.shrikant.swingtrader.db.InstrumentRepository;
import in.shrikant.swingtrader.db.InstrumentRepository.InstrumentRow;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * Pulls Kite's NSE instruments dump and stores the equity symbol →
 * instrument_token mapping. The historical candle API takes tokens, so this
 * must run (occasionally) before any candle download. Tokens are stable for
 * an instrument but the dump also reflects new listings/renames — refreshing
 * alongside the constituents snapshot is plenty.
 */
public final class InstrumentSync {

    private final KiteConnect kite;
    private final InstrumentRepository repository;

    public InstrumentSync(KiteConnect kite, InstrumentRepository repository) {
        this.kite = kite;
        this.repository = repository;
    }

    /**
     * Fetches the NSE dump, keeps cash-equity rows, upserts them.
     * Returns how many instruments were stored.
     */
    public int sync(LocalDate today) throws IOException, SQLException {
        List<Instrument> dump;
        try {
            dump = kite.getInstruments("NSE");
        } catch (KiteException e) {
            throw new IOException("Kite instruments dump failed: " + e.message
                    + " (code " + e.code + ")", e);
        }

        List<InstrumentRow> rows = dump.stream()
                .filter(i -> "EQ".equalsIgnoreCase(i.instrument_type))
                .filter(i -> "NSE".equalsIgnoreCase(i.segment))
                .map(i -> new InstrumentRow(i.tradingsymbol, i.instrument_token, i.exchange, i.name))
                .toList();
        if (rows.isEmpty()) {
            throw new IOException("Kite NSE instruments dump contained no cash-equity rows — "
                    + "refusing to overwrite the instruments table.");
        }
        repository.upsertAll(rows, today);
        return rows.size();
    }
}

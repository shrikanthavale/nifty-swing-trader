package in.shrikant.swingtrader.db;

import in.shrikant.swingtrader.db.InstrumentRepository.InstrumentRow;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstrumentRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 13);

    @Test
    void upsertThenLookupToken() throws Exception {
        try (Connection conn = Database.open(":memory:")) {
            InstrumentRepository repo = new InstrumentRepository(conn);
            repo.upsertAll(List.of(
                    new InstrumentRow("RELIANCE", 738561L, "NSE", "RELIANCE INDUSTRIES"),
                    new InstrumentRow("TCS", 2953217L, "NSE", "TATA CONSULTANCY SERV")), TODAY);

            assertEquals(2, repo.count());
            assertEquals(Optional.of(738561L), repo.tokenFor("RELIANCE"));
            assertTrue(repo.tokenFor("NOSUCH").isEmpty());
        }
    }

    @Test
    void reSyncUpdatesInsteadOfDuplicating() throws Exception {
        try (Connection conn = Database.open(":memory:")) {
            InstrumentRepository repo = new InstrumentRepository(conn);
            repo.upsertAll(List.of(
                    new InstrumentRow("RELIANCE", 111L, "NSE", "OLD NAME")), TODAY);
            repo.upsertAll(List.of(
                    new InstrumentRow("RELIANCE", 222L, "NSE", "NEW NAME")), TODAY.plusDays(30));

            assertEquals(1, repo.count());
            assertEquals(Optional.of(222L), repo.tokenFor("RELIANCE"));
        }
    }
}

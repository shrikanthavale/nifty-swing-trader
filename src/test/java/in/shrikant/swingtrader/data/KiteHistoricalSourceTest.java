package in.shrikant.swingtrader.data;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KiteHistoricalSourceTest {

    @Test
    void parsesKiteTimestampWithNonColonOffset() {
        assertEquals(LocalDate.of(2017, 12, 15),
                KiteHistoricalSource.parseDate("2017-12-15T09:15:00+0530"));
    }

    @Test
    void parsesPlainDate() {
        assertEquals(LocalDate.of(2026, 7, 13),
                KiteHistoricalSource.parseDate("2026-07-13"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class,
                () -> KiteHistoricalSource.parseDate("junk"));
        assertThrows(IllegalArgumentException.class,
                () -> KiteHistoricalSource.parseDate(null));
    }
}

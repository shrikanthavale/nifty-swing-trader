package com.shrikane.swingtrader.data;

import com.shrikane.swingtrader.db.ConstituentsRepository;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/** Universe backed by the dated constituents table. */
public final class DbUniverse implements Universe {

    private final ConstituentsRepository constituents;

    public DbUniverse(ConstituentsRepository constituents) {
        this.constituents = constituents;
    }

    @Override
    public List<String> membersOn(LocalDate date) {
        try {
            List<String> members = constituents.membersOn(date);
            if (members.isEmpty()) {
                throw new IllegalStateException("Universe is empty on " + date
                        + " — run the `universe` command to load NIFTY 100 constituents"
                        + " (or the date predates the first snapshot).");
            }
            return members;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load universe for " + date, e);
        }
    }
}

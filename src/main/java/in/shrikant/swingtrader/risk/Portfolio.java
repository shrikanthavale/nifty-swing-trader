package in.shrikant.swingtrader.risk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Current account state: cash + open positions. Mutated only by fills. */
public class Portfolio {

    private double cash;
    private final List<Position> positions = new ArrayList<>();

    public Portfolio(double startingCash) {
        this.cash = startingCash;
    }

    public double cash() { return cash; }

    public List<Position> openPositions() {
        return Collections.unmodifiableList(positions);
    }

    public boolean holds(String symbol) {
        return positions.stream().anyMatch(p -> p.symbol().equals(symbol));
    }

    public void applyBuy(Position position, double totalCost) {
        positions.add(position);
        cash -= totalCost;
    }

    public void applySell(String symbol, double totalProceeds) {
        positions.removeIf(p -> p.symbol().equals(symbol));
        cash += totalProceeds;
    }
}

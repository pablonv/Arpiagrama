package com.example.arpiagrama.physical;

/** Configuration of the physical workspace observed by the application. */
public final class PhysicalEnvironment {
    private final int gridRows;
    private final int gridColumns;

    public PhysicalEnvironment(int gridRows, int gridColumns) {
        if (gridRows <= 0 || gridColumns <= 0) throw new IllegalArgumentException("Grid dimensions must be positive");
        this.gridRows = gridRows;
        this.gridColumns = gridColumns;
    }

    public static PhysicalEnvironment arpiagramaTable() { return new PhysicalEnvironment(3, 3); }
    public int getGridRows() { return gridRows; }
    public int getGridColumns() { return gridColumns; }
}

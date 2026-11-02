package gui;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * "Load next state…" proposes the file after the current state's: numbers in file names count as numbers, so a series
 * T1 … T12 runs T1, T2, …, T10 and not T1, T10, T11, T12, T2. Needs no natives; run with {@code ant test}.
 */
public class MentalMapNamesTest {

    @Test
    public void numbersInNamesAreComparedAsNumbers() {
        List<String> names = Arrays.asList("T10.gml", "T2.gml", "T1.gml", "T12.gml", "T11.gml", "T3.gml");
        names.sort(MentalMapPanel::compareNames);
        assertEquals(Arrays.asList("T1.gml", "T2.gml", "T3.gml", "T10.gml", "T11.gml", "T12.gml"), names);
    }

    @Test
    public void textAndLeadingZeros() {
        assertTrue(MentalMapPanel.compareNames("state_9", "state_10") < 0);
        assertTrue(MentalMapPanel.compareNames("a2b", "a2c") < 0);
        assertTrue(MentalMapPanel.compareNames("T1", "T1a") < 0);
        assertTrue(MentalMapPanel.compareNames("T02", "T3") < 0);
        // equal numbers with other zeros are still two different names, in a fixed order
        assertTrue(MentalMapPanel.compareNames("T02", "T2") != 0);
        assertEquals(0, MentalMapPanel.compareNames("T2", "T2"));
    }
}

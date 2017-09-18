package org.goobi.api.rest;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Paths;

import org.junit.Assert;
import org.junit.Test;

public class CSVUtilTest {

    @Test
    public void testGetProperty() throws FileNotFoundException, IOException {
        CSVUtil util = new CSVUtil(Paths.get("resources/shoot.csv"));
        Assert.assertEquals("CP 003176", util.getValue("Reference"));
        Assert.assertEquals("Thomas S.G. Farnetti", util.getValue("Freelance Photog"));
        Assert.assertEquals("Wellcome Trust PR, Library blog post, Adamson Trust publicity", util.getValue("Intended Usage"));

        util = new CSVUtil(Paths.get("resources/shoot2.csv"));
        Assert.assertEquals("G1, 183 Euston Road", util.getValue("Location"));
    }

    @Test
    public void testProbablyBroken() throws FileNotFoundException, IOException {
        CSVUtil util = new CSVUtil(Paths.get("resources/broken_csv_maybe.csv"));
        Assert.assertEquals("EP 000016", util.getValue("Reference"));
        Assert.assertEquals("Objects", util.getValue("Shoot Type"));
        Assert.assertEquals("Photographic Studio, 183, 4th floor", util.getValue("Location"));
    }
}

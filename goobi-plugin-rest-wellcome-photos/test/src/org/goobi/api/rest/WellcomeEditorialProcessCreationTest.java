package org.goobi.api.rest;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class WellcomeEditorialProcessCreationTest {
    @Test
    public void testNewFilenameGen() throws NoSuchAlgorithmException, IOException {
        String referenceNumber = "EP TEST";
        List<Path> tifFiles = new ArrayList<>();
        tifFiles.add(Paths.get("resources/shatest/blablubb123.tif"));
        tifFiles.add(Paths.get("resources/shatest/blablubb125.tif"));
        tifFiles.add(Paths.get("resources/shatest/blablubb126.tif"));
        Map<String, String> oldFilesMap = new HashMap<>();
        oldFilesMap.put("77562bae82d32e4b80badf8c2cec99b15687a545", "EP_TEST_0001.tif");
        oldFilesMap.put("2", "EP_TEST_0002.tif");
        oldFilesMap.put("bee234cf6fc0c8d57016b5ea789b986dbd27ceac", "EP_TEST_0003.tif");
        List<String> checksums = new ArrayList<>();
        int max = WellcomeEditorialProcessCreation.computeHighestNumAndSha1Sums(tifFiles, oldFilesMap, checksums);
        List<Path> newTifFiles = new ArrayList<>();
        WellcomeEditorialProcessCreation.generateNewNamesEPs3(tifFiles, referenceNumber, newTifFiles, oldFilesMap, checksums, max);
        System.out.println(newTifFiles);
    }

}

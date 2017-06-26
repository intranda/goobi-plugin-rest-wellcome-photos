package org.goobi.api.rest;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

public class CSVUtil {
    private Map<String, Integer> indexMap;
    private List<String[]> values;

    public CSVUtil(Path csvFile) throws FileNotFoundException, IOException {
        this.indexMap = new HashMap<>();
        this.values = new ArrayList<>();
        readFile(csvFile);
    }

    private void readFile(Path csvFile) throws FileNotFoundException, IOException {
        boolean firstLine = true;

        try (Reader r = new FileReader(csvFile.toFile())) {
            Iterable<CSVRecord> records = CSVFormat.RFC4180.parse(r);
            for (CSVRecord record : records) {
                if (firstLine) {
                    readIndex(record);
                    firstLine = false;
                } else {
                    readLine(record);
                }
            }
        }
    }

    private void readIndex(CSVRecord record) {
        int idx = 0;
        Iterator<String> titleIt = record.iterator();
        while (titleIt.hasNext()) {
            indexMap.put(titleIt.next(), idx);
            idx++;
        }
    }

    private void readLine(CSVRecord record) {
        int idx = 0;
        String[] lineValues = new String[indexMap.size()];
        Iterator<String> lineIt = record.iterator();
        while (lineIt.hasNext()) {
            lineValues[idx] = lineIt.next();
            idx++;
        }
        this.values.add(lineValues);
    }

    public String getValue(String name, int row) {
        Integer index = this.indexMap.get(name);
        if (index == null) {
            return null;
        }
        return values.get(row)[index];
    }

    public String getValue(String name) {
        return this.getValue(name, 0);
    }
}

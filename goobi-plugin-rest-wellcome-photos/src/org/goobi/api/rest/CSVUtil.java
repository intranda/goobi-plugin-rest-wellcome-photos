package org.goobi.api.rest;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class CSVUtil {
    private Map<String, Integer> indexMap;
    private List<String[]> values;

    public CSVUtil(Path csvFile) throws FileNotFoundException, IOException {
        readFile(csvFile);
    }

    private void readFile(Path csvFile) throws FileNotFoundException, IOException {
        boolean firstLine = true;
        try (BufferedReader br = new BufferedReader(new FileReader(csvFile.toFile()))) {
            for (String line; (line = br.readLine()) != null;) {
                if (firstLine) {
                    readIndex(line);
                    firstLine = false;
                } else {
                    this.values.add(line.split(","));
                }
            }
        }
    }

    private void readIndex(String line) {
        int idx = 0;
        for (String title : line.split(",")) {
            indexMap.put(title, idx);
            idx++;
        }
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

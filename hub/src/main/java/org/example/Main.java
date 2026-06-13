package org.example;

import org.voucherhub.common.ExperimentRunner;

public class Main {
    public static void main(String[] args) throws Exception {
        ExperimentRunner.main(args.length == 0
                ? new String[]{"--mode", "voucherhub-griefing"}
                : args);
    }
}

#!/bin/bash
mkdir -p bin
javac -cp "lib/*" -sourcepath src -d bin src/bench/cli/CLIApp.java
java -cp "bin:lib/*" bench.cli.CLIApp "$@"

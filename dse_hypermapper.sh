#!/usr/bin/env bash

benchmarks=("BlackScholes" "DotProduct" "GDA" "Kmeans" "MatMult_outer"  "OuterProduct" "Sobel" "SW" "TPCHQ6")

for benchmark in "${benchmarks[@]}"
do
    echo "bin/spatial ${benchmark} --experiment --threads 16"
    bin/spatial ${benchmark} --hypermapper --threads 16 2>&1 | tee ${benchmark}.log
done

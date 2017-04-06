#!/bin/bash
if [[ "$USE_IDEAL_DRAM" = "0" || "$USE_IDEAL_DRAM" = "1" ]]; then
	ideal=$USE_IDEAL_DRAM
else
	ideal=0
fi
export USE_IDEAL_DRAM=$ideal
export DRAMSIM_HOME=`pwd`/verilog/DRAMSim2
export LD_LIBRARY_PATH=${DRAMSIM_HOME}:$LD_LIBRARY_PATH
./Top $@
if [[ "$USE_IDEAL_DRAM" = "1" ]]; then
	echo "Ideal DRAM Simulation"
elif [[ "$USE_IDEAL_DRAM" = "0" ]]; then
	echo "Realistic DRAM Simulation"
else
	echo "UNKNOWN DRAM SIMULATION!"
fi

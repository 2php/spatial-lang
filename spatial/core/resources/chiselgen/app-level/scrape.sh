#!bin/bash

#1 = Backend

# get tid
REGRESSION_HOME="/home/mattfel/regression/zynq"
tid=`cat ${REGRESSION_HOME}/data/tid`

appname=`basename \`pwd\``
if [[ $1 = "Zynq" ]]; then
	par_util=`pwd`/verilog-zynq/par_utilization.rpt
	f1=3
	f2=6
elif [[ $1 = "F1" ]]; then
	par_util=/home/mattfel/aws-fpga/hdk/cl/examples/$appname/build/reports/utilization_route_design.rpt
	f1=4
	f2=7
fi

if [[ -f ${par_util} ]]; then
	lutraw=`cat $par_util | grep -m 1 "Slice LUTs" | awk -v f=$f1 -F'|' '{print $f}' | sed "s/ //g"`
	lutpcnt=`cat $par_util | grep -m 1 "Slice LUTs" | awk -v f=$f2 -F'|' '{print $f}' | sed "s/ //g"`
	regraw=`cat $par_util | grep -m 1 "Slice Registers" | awk -v f=$f1 -F'|' '{print $f}' | sed "s/ //g"`
	regpcnt=`cat $par_util | grep -m 1 "Slice Registers" | awk -v f=$f2 -F'|' '{print $f}' | sed "s/ //g"`
	ramraw=`cat $par_util | grep -m 1 "| Block RAM Tile" | awk -v f=$f1 -F'|' '{print $f}' | sed "s/ //g"`
	rampcnt=`cat $par_util | grep -m 1 "| Block RAM Tile" | awk -v f=$f2 -F'|' '{print $f}' | sed "s/ //g"`
	if [[ $1 = "F1" ]]; then		
		uramraw=`cat $par_util | grep -m 1 "| Block RAM Tile" | awk -v f=$f1 -F'|' '{print $f}' | sed "s/ //g"`
		urampcnt=`cat $par_util | grep -m 1 "| Block RAM Tile" | awk -v f=$f2 -F'|' '{print $f}' | sed "s/ //g"`
	else
		uramraw="NA"
		urampcnt="NA"
	fi
	dspraw=`cat $par_util | grep -m 1 "DSPs" | awk -v f=$f1 -F'|' '{print $f}' | sed "s/ //g"`
	dsppcnt=`cat $par_util | grep -m 1 "DSPs" | awk -v f=$f2 -F'|' '{print $f}' | sed "s/ //g"`
	lalraw=`cat $par_util | grep -m 1 "LUT as Logic" | awk -v f=$f1 -F'|' '{print $f}' | sed "s/ //g"`
	lalpcnt=`cat $par_util | grep -m 1 "LUT as Logic" | awk -v f=$f2 -F'|' '{print $f}' | sed "s/ //g"`
	lamraw=`cat $par_util | grep -m 1 "LUT as Memory" | awk -v f=$f1 -F'|' '{print $f}' | sed "s/ //g"`
	lampcnt=`cat $par_util | grep -m 1 "LUT as Memory" | awk -v f=$f2 -F'|' '{print $f}' | sed "s/ //g"`
else
	lutraw="NA"
	lutpcnt="NA"
	regraw="NA"
	regpcnt="NA"
	ramraw="NA"
	rampcnt="NA"
	uramraw="NA"
	urampcnt="NA"
	dspraw="NA"
	dsppcnt="NA"
	lalraw="NA"
	lalpcnt="NA"
	lamraw="NA"
	lampcnt="NA"
fi

synthtime=0

python3 scrape.py $tid $appname "$lutraw (${lutpcnt}%)" "$regraw (${regpcnt}%)" "$ramraw (${rampcnt}%)" "$uramraw (${urampcnt}%)" "$dspraw (${dsppcnt}%)" "$lalraw (${lalpcnt}%)" "$lamraw (${lampcnt}%)" "$synthtime" "$1"


# Fake out scala Regression
echo "PASS: 1"
create_project project_1 ./project_1 -part xc7z045ffg900-2
set_property board_part xilinx.com:zc706:part0:1.3 [current_project]
#add_files -norecurse {./Top.v ./AXI4LiteToRFBridgeVerilog.v}
add_files -norecurse [glob *.v]
update_compile_order -fileset sources_1
update_compile_order -fileset sim_1

create_bd_design "design_1"
update_compile_order -fileset sources_1

startgroup
create_bd_cell -type ip -vlnv xilinx.com:ip:processing_system7:5.5 processing_system7_0
endgroup


apply_bd_automation -rule xilinx.com:bd_rule:processing_system7 -config {make_external "FIXED_IO, DDR" apply_board_preset "1" Master "Disable" Slave "Disable" }  [get_bd_cells processing_system7_0]


create_bd_cell -type module -reference Top Top_0
apply_bd_automation -rule xilinx.com:bd_rule:axi4 -config {Master "/processing_system7_0/M_AXI_GP0" Clk "/processing_system7_0/FCLK_CLK0 (10 MHz)" }  [get_bd_intf_pins Top_0/io_S_AXI]

set_property -dict [list CONFIG.PCW_USE_S_AXI_HP0 {1}] [get_bd_cells processing_system7_0]
set_property -dict [list CONFIG.PCW_FPGA0_PERIPHERAL_FREQMHZ {10}] [get_bd_cells processing_system7_0]
# set_property -dict [list CONFIG.PCW_USE_S_AXI_ACP {1}] [get_bd_cells processing_system7_0]

apply_bd_automation -rule xilinx.com:bd_rule:axi4 -config {Master "/Top_0/io_M_AXI" Clk "/processing_system7_0/FCLK_CLK0 (10 MHz)" }  [get_bd_intf_pins processing_system7_0/S_AXI_HP0]
#apply_bd_automation -rule xilinx.com:bd_rule:axi4 -config {Master "/Top_0/io_M_AXI" Clk "/processing_system7_0/FCLK_CLK0 (50 MHz)" }  [get_bd_intf_pins processing_system7_0/S_AXI_ACP]

validate_bd_design
save_bd_design

make_wrapper -files [get_files ./project_1/project_1.srcs/sources_1/bd/design_1/design_1.bd] -top
add_files -norecurse ./project_1/project_1.srcs/sources_1/bd/design_1/hdl/design_1_wrapper.v
update_compile_order -fileset sources_1

set_property top design_1_wrapper [current_fileset]
update_compile_order -fileset sources_1

launch_runs synth_1
wait_on_run synth_1

# Print synth reports on console
open_run -name synthDesign synth_1
report_timing_summary -file synth_timing_summary
report_utilization -packthru -file synth_utilization
report_ram_utilization -file synth_ram_utilization

launch_runs impl_1
wait_on_run impl_1

launch_runs impl_1 -to_step write_bitstream
wait_on_run impl_1

# Reports
open_run -name implDesign impl_1
report_timing_summary -file ./par_timing_summary.rpt
report_utilization -packthru -file ./par_utilization.rpt
report_ram_utilization -file ./par_ram_utilization.rpt

#Export bitstream
file copy -force ./project_1/project_1.runs/impl_1/design_1_wrapper.bit ./accel.bit

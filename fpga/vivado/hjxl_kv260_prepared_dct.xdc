# Out-of-context timing contract for the direct prepared-DCT accelerator.
# The future block design must drive every AXI interface from ap_clk or add
# explicit clock-domain crossings before this constraint can remain valid.
if {![info exists hjxl_clock_period_ns]} {
  set hjxl_clock_period_ns 5.000
}
set hjxl_clock_half_period_ns [expr {$hjxl_clock_period_ns / 2.0}]
create_clock -name ap_clk -period $hjxl_clock_period_ns \
  -waveform [list 0.000 $hjxl_clock_half_period_ns] [get_ports ap_clk]
set_clock_uncertainty 0.200 [get_clocks ap_clk]

# Reserve one nanosecond on either side of the accelerator for block-design
# routing and neighboring logic. Reset is asynchronous and intentionally
# excluded from synchronous I/O timing.
set hjxl_data_inputs [remove_from_collection [all_inputs] [get_ports {ap_clk ap_rst_n}]]
if {[llength $hjxl_data_inputs] > 0} {
  set_input_delay -clock ap_clk 1.000 $hjxl_data_inputs
}
set hjxl_data_outputs [all_outputs]
if {[llength $hjxl_data_outputs] > 0} {
  set_output_delay -clock ap_clk 1.000 $hjxl_data_outputs
}
set_false_path -from [get_ports ap_rst_n]

# Reproducible out-of-context synthesis for HjxlKv260PreparedDctTop.

proc hjxl_usage {} {
  puts "Usage: vivado -mode batch -source fpga/vivado/synth.tcl -tclargs ?options?"
  puts "       tclsh fpga/vivado/synth.tcl --preflight-only ?options?"
  puts "Options:"
  puts "  --rtl-dir PATH              generated RTL directory"
  puts "  --out-dir PATH              report/checkpoint output directory"
  puts "  --part PART                 FPGA part override"
  puts "  --board-part BOARD          Vivado board-part override"
  puts "  --clock-period-ns PERIOD    clock-period metadata override"
  puts "  --allow-negative-slack      report negative slack without failing"
  puts "  --preflight-only            validate inputs without Vivado"
  puts "  --help                      show this message"
}

proc hjxl_take_value {argv index option} {
  set value_index [expr {$index + 1}]
  if {$value_index >= [llength $argv]} {
    error "$option requires a value"
  }
  return [lindex $argv $value_index]
}

proc hjxl_validate_xdc_syntax {constraint_file clock_period_ns} {
  set checker [interp create]
  set prelude {
    proc get_ports {args} { return $args }
    proc get_clocks {args} { return $args }
    proc create_clock {args} {}
    proc set_clock_uncertainty {args} {}
    proc all_inputs {} { return {ap_clk ap_rst_n input_data} }
    proc all_outputs {} { return {output_data} }
    proc remove_from_collection {collection removal} { return {input_data} }
    proc set_input_delay {args} {}
    proc set_output_delay {args} {}
    proc set_false_path {args} {}
  }
  $checker eval $prelude
  $checker eval [list set hjxl_clock_period_ns $clock_period_ns]
  set status [catch {$checker eval [list source $constraint_file]} message options]
  interp delete $checker
  if {$status != 0} {
    return -options $options "constraint syntax check failed: $message"
  }
}

set script_dir [file dirname [file normalize [info script]]]
set repo_root [file normalize [file join $script_dir ../..]]
set top HjxlKv260PreparedDctTop
set part xck26-sfvc784-2LV-c
set board_part xilinx.com:kv260_som:part0:1.4
set clock_period_ns 5.000
set rtl_dir [file join $repo_root generated-kv260-prepared-dct-top]
set out_dir [file join $repo_root build vivado kv260-prepared-dct]
set constraint_file [file join $script_dir hjxl_kv260_prepared_dct.xdc]
set preflight_only 0
set allow_negative_slack 0

for {set i 0} {$i < [llength $argv]} {incr i} {
  set option [lindex $argv $i]
  switch -- $option {
    --rtl-dir {
      set rtl_dir [hjxl_take_value $argv $i $option]
      incr i
    }
    --out-dir {
      set out_dir [hjxl_take_value $argv $i $option]
      incr i
    }
    --part {
      set part [hjxl_take_value $argv $i $option]
      incr i
    }
    --board-part {
      set board_part [hjxl_take_value $argv $i $option]
      incr i
    }
    --clock-period-ns {
      set clock_period_ns [hjxl_take_value $argv $i $option]
      incr i
    }
    --allow-negative-slack {
      set allow_negative_slack 1
    }
    --preflight-only {
      set preflight_only 1
    }
    --help {
      hjxl_usage
      exit 0
    }
    default {
      error "unknown option: $option"
    }
  }
}

set rtl_dir [file normalize $rtl_dir]
set out_dir [file normalize $out_dir]
if {![string is double -strict $clock_period_ns] || $clock_period_ns <= 0.0} {
  error "--clock-period-ns must be a positive number, got: $clock_period_ns"
}
if {![regexp {^[A-Za-z_][A-Za-z0-9_$]*$} $top]} {
  error "invalid top module name: $top"
}
if {![file isfile $constraint_file]} {
  error "constraint file not found: $constraint_file"
}
hjxl_validate_xdc_syntax $constraint_file $clock_period_ns

set filelist [file join $rtl_dir filelist.f]
if {![file isfile $filelist]} {
  error "generated RTL file list not found: $filelist; run sbt 'runMain hjxl.ElaborateKv260PreparedDctTop' first"
}

set handle [open $filelist r]
set filelist_text [read $handle]
close $handle
set rtl_files {}
set seen_files [dict create]
set line_number 0
foreach raw_line [split $filelist_text "\n"] {
  incr line_number
  set line [string trim $raw_line]
  if {$line eq "" || [string match "#*" $line]} {
    continue
  }
  if {[file pathtype $line] eq "absolute"} {
    error "$filelist:$line_number: absolute RTL paths are not reproducible: $line"
  }
  if {[file extension $line] ne ".sv"} {
    error "$filelist:$line_number: expected a .sv entry, got: $line"
  }
  set rtl_file [file normalize [file join $rtl_dir $line]]
  if {![file isfile $rtl_file]} {
    error "$filelist:$line_number: RTL file not found: $rtl_file"
  }
  if {[dict exists $seen_files $rtl_file]} {
    error "$filelist:$line_number: duplicate RTL file: $line"
  }
  dict set seen_files $rtl_file 1
  lappend rtl_files $rtl_file
}
if {[llength $rtl_files] == 0} {
  error "$filelist contains no RTL files"
}

set top_file [file normalize [file join $rtl_dir ${top}.sv]]
if {![dict exists $seen_files $top_file]} {
  error "$filelist does not include the top module file: $top_file"
}
set top_handle [open $top_file r]
set top_text [read $top_handle]
close $top_handle
set top_pattern [format {module[[:space:]]+%s[[:space:]#(]} $top]
if {![regexp $top_pattern $top_text]} {
  error "$top_file does not declare module $top"
}

puts "HJXL Vivado synthesis preflight"
puts "  repository:   $repo_root"
puts "  top:          $top"
puts "  part:         $part"
puts "  board part:   $board_part"
puts "  clock period: $clock_period_ns ns"
puts "  RTL files:    [llength $rtl_files]"
puts "  output:       $out_dir"

if {$preflight_only} {
  puts "HJXL Vivado synthesis preflight passed"
  exit 0
}

file mkdir $out_dir
create_project -in_memory -part $part
set matching_board_parts [get_board_parts -quiet $board_part]
if {[llength $matching_board_parts] == 1} {
  set_property board_part $board_part [current_project]
} else {
  puts "WARNING: board part $board_part is not installed; continuing with device part $part"
}

foreach rtl_file $rtl_files {
  read_verilog -sv $rtl_file
}
set hjxl_clock_period_ns $clock_period_ns
read_xdc $constraint_file
synth_design -top $top -part $part -mode out_of_context -flatten_hierarchy rebuilt

write_checkpoint -force [file join $out_dir post_synth.dcp]
report_utilization -hierarchical -file [file join $out_dir utilization_hierarchical.rpt]
report_utilization -file [file join $out_dir utilization.rpt]
report_timing_summary -delay_type max -report_unconstrained -check_timing_verbose \
  -max_paths 20 -file [file join $out_dir timing_summary.rpt]
report_timing -delay_type max -max_paths 20 -nworst 1 \
  -file [file join $out_dir timing_paths.rpt]
report_methodology -file [file join $out_dir methodology.rpt]
report_clock_utilization -file [file join $out_dir clock_utilization.rpt]
check_timing -verbose -file [file join $out_dir check_timing.rpt]

set timing_paths [get_timing_paths -quiet -delay_type max -max_paths 1 -nworst 1]
if {[llength $timing_paths] == 0} {
  error "no maximum-delay timing path was found; inspect the constraints and generated reports"
}
set worst_slack [get_property SLACK [lindex $timing_paths 0]]
set timing_met [expr {$worst_slack >= 0.0}]

set summary_file [file join $out_dir summary.txt]
set summary_handle [open $summary_file w]
puts $summary_handle "top=$top"
puts $summary_handle "part=$part"
puts $summary_handle "board_part=$board_part"
puts $summary_handle "clock_period_ns=$clock_period_ns"
puts $summary_handle "worst_slack_ns=$worst_slack"
puts $summary_handle "timing_met=$timing_met"
puts $summary_handle "vivado_version=[version -short]"
close $summary_handle

puts "HJXL synthesis reports written to $out_dir"
puts "Worst post-synthesis slack: $worst_slack ns"
if {!$timing_met && !$allow_negative_slack} {
  error "post-synthesis timing failed; rerun with --allow-negative-slack only for exploratory reporting"
}

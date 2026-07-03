# hjxl

Chisel hardware project scaffolded from
[chipsalliance/chisel-template](https://github.com/chipsalliance/chisel-template).

## Requirements

- JDK 21 or newer
- sbt, or the checked-in Mill bootstrap script
- Verilator for simulator-backed tests

## Build

Run the example test suite with sbt:

```sh
sbt test
```

Or with Mill:

```sh
./mill hjxl.test
```

Generate the example GCD SystemVerilog:

```sh
sbt 'runMain gcd.GCD'
```

## Versions

- Scala 2.13.18
- Chisel 7.13.0
- ScalaTest 3.2.19
- sbt 1.12.13
- Mill 1.1.7

## License

GPLv3. See [LICENSE](LICENSE).

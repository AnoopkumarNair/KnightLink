# Raw captures

Put original capture files here: ELM327 session logs (`log.txt`) and Android Bluetooth HCI snoop logs (`btsnoop_hci.log`).

Everything in this folder except this file is ignored by git, because raw captures can contain a VIN, Bluetooth MAC addresses and location. Scrubbed excerpts that tests depend on go in `fixtures/golden/`.

from conan import ConanFile
import hashlib
import os


class KkRepoConanPerformanceFixture(ConanFile):
    name = "kkrepo-conan-performance"
    version = "1.0.0"
    package_type = "application"

    def package(self):
        """Generate a deterministic, poorly-compressible 4 MiB package payload."""
        destination = os.path.join(self.package_folder, "payload.bin")
        remaining = 4 * 1024 * 1024
        counter = 0
        with open(destination, "wb") as output:
            while remaining:
                block = hashlib.sha256(
                    b"kkrepo-conan-performance-v1" + counter.to_bytes(8, "big")
                ).digest()
                output.write(block[:remaining])
                remaining -= min(len(block), remaining)
                counter += 1

    def package_id(self):
        self.info.clear()

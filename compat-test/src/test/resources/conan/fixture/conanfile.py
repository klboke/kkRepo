from conan import ConanFile
from conan.tools.files import copy
import os


class KkRepoConanFixture(ConanFile):
    name = "kkrepo-conan-fixture"
    version = "1.0.0"
    package_type = "header-library"
    exports_sources = "include/*"
    no_copy_source = True

    def package(self):
        copy(self, "*.h", src=os.path.join(self.source_folder, "include"),
             dst=os.path.join(self.package_folder, "include"))

    def package_id(self):
        self.info.clear()

    def package_info(self):
        self.cpp_info.includedirs = ["include"]

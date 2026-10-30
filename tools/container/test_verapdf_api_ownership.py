import pathlib
import tempfile
import unittest
import zipfile
from prepare_verapdf_materials import verify_validator_classes


class VeraPdfApiOwnershipTest(unittest.TestCase):
    def write(self, root, file, *classes):
        with zipfile.ZipFile(root / file, "w") as jar:
            for name in classes:
                jar.writestr(name, b"fixture")

    def test_two_different_artifact_names_cannot_supply_the_same_legacy_api(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            for name in ("javax/xml/bind/JAXBContext.class", "javax/activation/DataHandler.class"):
                with self.subTest(api=name):
                    self.write(root, "legacy-api.jar", "javax/xml/bind/JAXBContext.class", "javax/activation/DataHandler.class")
                    verify_validator_classes(root)
                    self.write(root, "other-artifact.jar", name)
                    with self.assertRaisesRegex(AssertionError, "exactly one validator API owner"):
                        verify_validator_classes(root)
                    (root / "other-artifact.jar").unlink()

    def test_missing_or_wrong_namespace_apis_never_pass_material_preparation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            with self.assertRaisesRegex(AssertionError, "exactly one validator API owner"):
                verify_validator_classes(root)
            self.write(root, "legacy-api.jar", "javax/xml/bind/JAXBContext.class", "javax/activation/DataHandler.class")
            for name in ("jakarta/xml/bind/JAXBContext.class", "jakarta/activation/DataHandler.class"):
                with self.subTest(api=name):
                    self.write(root, "wrong-namespace.jar", name)
                    with self.assertRaisesRegex(AssertionError, "must retain javax APIs"):
                        verify_validator_classes(root)
                    (root / "wrong-namespace.jar").unlink()

    def test_duplicate_implementation_classes_are_rejected_but_module_descriptors_are_not_apis(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.write(root, "api.jar", "javax/xml/bind/JAXBContext.class", "javax/activation/DataHandler.class", "module-info.class")
            self.write(root, "core.jar", "com/sun/xml/bind/Core.class", "module-info.class")
            verify_validator_classes(root)
            self.write(root, "implementation.jar", "com/sun/xml/bind/Core.class")
            with self.assertRaisesRegex(AssertionError, "Duplicate validator class entries"):
                verify_validator_classes(root)

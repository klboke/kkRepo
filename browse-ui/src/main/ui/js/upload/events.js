import { fieldsState, renderUploadFields } from "./fields.js";
import { updateUploadPath } from "./paths.js";
import { uploadSelectedAsset } from "./submit.js";

export function bindUploadEvents() {
  document.getElementById("upload-repository").addEventListener("change", () => {
    fieldsState.uploadAssetCount = 1;
    renderUploadFields();
    updateUploadPath();
  });

  document.getElementById("upload-fields").addEventListener("input", updateUploadPath);

  document.getElementById("upload-fields").addEventListener("change", updateUploadPath);

  document.getElementById("upload-form").addEventListener("submit", uploadSelectedAsset);
}

import subprocess
import re
from collections import defaultdict

def run():
    binary_path = "/data/data/com.termux/files/usr/bin/agy.va39"
    cmd = ["strings", binary_path]
    res = subprocess.run(cmd, stdout=subprocess.PIPE, text=True, errors="ignore")
    lines = res.stdout.splitlines()

    known_services = [
        "google.internal.cloud.code.v1internal.CloudCode",
        "google.internal.cloud.code.v1internal.JetskiService",
        "google.internal.cloud.code.v1internal.PredictionService",
        "google.cloud.aiplatform.master.PredictionService",
        "google.cloud.aiplatform.v1beta1.PredictionService",
        "google.cloud.businessaicode.v1beta.ManagementService",
        "google.cloud.businessaicode.v1beta.PredictionService",
        "google.cloud.businessaicode.v1beta.TelemetryService",
        "google.cloud.businessaicode.v1main.PredictionService",
        "google.gca.aicode.v1alpha.PredictionService",
        "google.gca.aicode.v1main.PredictionService",
        "google.cloud.speech.v1p1beta1.Speech",
        "google.longrunning.Operations",
        "jetski.product.v1.ConversationService",
        "exa.seat_management_pb.SeatManagementService",
        "exa.cascade_plugins_pb.CascadePluginsService",
        "exa.extension_server_pb.ExtensionServerService",
        "exa.api_server_pb.ApiServerService",
        "exa.opensearch_clients_pb.KnowledgeBaseService",
        "exa.opensearch_clients_pb.CodeIndexService"
    ]

    methods_by_service = defaultdict(set)
    for svc in known_services:
        prefix = "/" + svc + "/"
        for line in lines:
            idx = line.find(prefix)
            while idx != -1:
                rest = line[idx + len(prefix):]
                m = re.match(r'^[A-Z][a-zA-Z0-9]+', rest)
                if m:
                    # Clean trailing artifacts
                    name = m.group(0)
                    for bad in ['Failed', 'failed', 'starting', 'customization', 'elicit', 'planner', 'compositeCustomization', 'project', 'unsupported', 'RoundTripper', 'save', 'The', 'Warning', 'cannot', 'attempted', 'core']:
                        if name.endswith(bad) and len(name) > len(bad):
                            name = name[:-len(bad)]
                    methods_by_service[svc].add(name)
                idx = line.find(prefix, idx + 1)

    with open("/data/user/0/com.termux/files/home/.gemini/antigravity-cli/brain/37642fbe-6e57-4020-96b7-236e0a38422e/scratch/grpc_apis.txt", "w") as out:
        for svc in known_services:
            out.write(f"### `{svc}` ({len(methods_by_service[svc])} RPCs)\n")
            for m in sorted(methods_by_service[svc]):
                out.write(f"- `/{svc}/{m}`\n")
            out.write("\n")

if __name__ == "__main__":
    run()

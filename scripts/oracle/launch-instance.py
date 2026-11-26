"""Creates the Oracle Always Free VM, retrying while Oracle has no Ampere A1 capacity ("Out of host capacity").

    python scripts/oracle/launch-instance.py --compartment <ocid> --subnet <ocid> --image <ocid> --ad <name>

Needs the OCI Python SDK (pip install oci) and an API key in ~/.oci/config (Documentation/guides/deploying-oracle.md).
Every --interval seconds (5 min by default, Oracle refuses faster retries with "Too many requests") it tries each shape
of --shapes in turn (2 OCPU / 12 GB, then 1 / 6: smaller requests pass more often). It stops as soon as an instance
named --name exists (created by this script or by the console / a stack Apply meanwhile), and prints its public IP.
Stops on any other error (bad OCID, free limit reached, key refused) instead of retrying blindly.
"""
import argparse
import random
import sys
import time
from datetime import datetime
from pathlib import Path

import oci


def log(message):
    print(f"[{datetime.now():%Y-%m-%d %H:%M:%S}] {message}", flush=True)


def existing(compute, compartment, name):
    """The live instance called name, if any (anything but terminating/terminated)."""
    for instance in oci.pagination.list_call_get_all_results(
            compute.list_instances, compartment, display_name=name).data:
        if instance.lifecycle_state not in ("TERMINATING", "TERMINATED"):
            return instance
    return None


def public_ip(compute, network, compartment, instance):
    for attachment in compute.list_vnic_attachments(compartment, instance_id=instance.id).data:
        vnic = network.get_vnic(attachment.vnic_id).data
        if vnic.public_ip:
            return vnic.public_ip
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--compartment", required=True, help="compartment OCID (the tenancy OCID for the root one)")
    parser.add_argument("--subnet", required=True, help="public subnet OCID")
    parser.add_argument("--image", required=True, help="Ubuntu aarch64 image OCID")
    parser.add_argument("--ad", required=True, help="availability domain, e.g. WdNB:EU-PARIS-1-AD-1")
    parser.add_argument("--ssh-key", default=str(Path.home() / ".ssh" / "id_ed25519_phantasmon_prod.pub"))
    parser.add_argument("--name", default="phantasmon")
    parser.add_argument("--shapes", default="2:12,1:6", help="OCPU:GB pairs, tried in this order")
    parser.add_argument("--boot-gb", type=int, default=50)
    parser.add_argument("--interval", type=int, default=300, help="seconds between rounds (at least 300)")
    parser.add_argument("--profile", default="DEFAULT")
    args = parser.parse_args()

    config = oci.config.from_file(profile_name=args.profile)
    oci.config.validate_config(config)
    compute = oci.core.ComputeClient(config)
    network = oci.core.VirtualNetworkClient(config)
    ssh_key = Path(args.ssh_key).read_text(encoding="utf-8").strip()
    shapes = [tuple(float(v) for v in pair.split(":")) for pair in args.shapes.split(",")]
    interval = max(args.interval, 300)

    attempt = 0
    while True:
        instance = existing(compute, args.compartment, args.name)
        if instance is None:
            for ocpus, memory in shapes:
                attempt += 1
                details = oci.core.models.LaunchInstanceDetails(
                    compartment_id=args.compartment,
                    availability_domain=args.ad,
                    display_name=args.name,
                    shape="VM.Standard.A1.Flex",
                    shape_config=oci.core.models.LaunchInstanceShapeConfigDetails(ocpus=ocpus, memory_in_gbs=memory),
                    source_details=oci.core.models.InstanceSourceViaImageDetails(
                        image_id=args.image, boot_volume_size_in_gbs=args.boot_gb),
                    create_vnic_details=oci.core.models.CreateVnicDetails(subnet_id=args.subnet, assign_public_ip=True),
                    metadata={"ssh_authorized_keys": ssh_key},
                )
                try:
                    instance = compute.launch_instance(details).data
                    log(f"Attempt {attempt}: {ocpus:g} OCPU / {memory:g} GB accepted, the instance is being created.")
                    break
                except oci.exceptions.ServiceError as error:
                    if error.status == 500 and "capacity" in (error.message or "").lower():
                        log(f"Attempt {attempt}: {ocpus:g} OCPU / {memory:g} GB, no capacity yet.")
                        continue
                    if error.status == 429:
                        log(f"Attempt {attempt}: too many requests, waiting longer.")
                        time.sleep(interval)
                        break
                    log(f"Attempt {attempt}: stopped on an error that retrying will not fix: {error.status} {error.code} {error.message}")
                    return 1
        if instance is not None:
            log(f"Instance {instance.display_name} found ({instance.lifecycle_state}), waiting until it runs...")
            instance = oci.wait_until(compute, compute.get_instance(instance.id), "lifecycle_state", "RUNNING",
                                      max_wait_seconds=1200).data
            ip = public_ip(compute, network, args.compartment, instance)
            log(f"RUNNING: {instance.shape_config.ocpus:g} OCPU / {instance.shape_config.memory_in_gbs:g} GB, public IP {ip}")
            return 0
        time.sleep(interval + random.randint(0, 30))


if __name__ == "__main__":
    sys.exit(main())

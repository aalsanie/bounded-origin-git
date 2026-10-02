import {createHash} from "node:crypto";

export function repository(algorithm = "sha1") {
  const hashName = algorithm === "sha256" ? "sha256" : "sha1";
  const objects = new Map();
  const reads = new Set();

  const add = (type, value) => {
    const data = bytes(value);
    const header = Buffer.from(type + " " + data.length + "\0", "ascii");
    const oid = createHash(hashName).update(header).update(data).digest("hex");
    objects.set(oid, {type, data: new Uint8Array(data)});
    return oid;
  };

  const tree = entries => {
    const sorted = [...entries].sort(
        (left, right) => Buffer.compare(Buffer.from(left.name), Buffer.from(right.name)));
    const chunks = [];
    for (const entry of sorted) {
      chunks.push(Buffer.from(entry.mode + " " + entry.name + "\0", "utf8"));
      chunks.push(Buffer.from(entry.oid, "hex"));
    }
    return add("tree", Buffer.concat(chunks));
  };

  const commit = treeOid =>
      add(
          "commit",
          "tree "
              + treeOid
              + "\nauthor Test <test@example.com> 0 +0000\n"
              + "committer Test <test@example.com> 0 +0000\n\nfixture\n");

  const tag = (target, type) =>
      add(
          "tag",
          "object "
              + target
              + "\ntype "
              + type
              + "\ntag v1\ntagger Test <test@example.com> 0 +0000\n\nfixture\n");

  const readObject = async oid => {
    reads.add(oid);
    const object = objects.get(oid);
    if (!object) {
      throw new Error("missing fixture object " + oid);
    }
    return {type: object.type, data: new Uint8Array(object.data)};
  };

  return {add, tree, commit, tag, readObject, reads};
}

function bytes(value) {
  if (value instanceof Uint8Array) {
    return Buffer.from(value);
  }
  return Buffer.from(value, "utf8");
}

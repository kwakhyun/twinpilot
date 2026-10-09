# TwinPilot NCP 인프라: VPC + 서브넷(노드·LB·NAT) + NAT Gateway + NKS 클러스터·노드풀 + Object Storage
#
# 주의: 이 환경에서는 Terraform 레지스트리 접근이 막혀 `terraform validate`를 실행하지 못했다(`terraform fmt`만 확인).
#       적용 전 사용하는 ncloud provider 버전의 문서와 리소스 인자를 대조한다.

resource "ncloud_vpc" "this" {
  name            = "${var.name}-vpc"
  ipv4_cidr_block = var.vpc_cidr
}

# 워커 노드: 사설 서브넷(외부 유출은 NAT Gateway 경유)
resource "ncloud_subnet" "node" {
  name           = "${var.name}-node"
  vpc_no         = ncloud_vpc.this.id
  subnet         = cidrsubnet(var.vpc_cidr, 8, 1)
  zone           = var.zone
  network_acl_no = ncloud_vpc.this.default_network_acl_no
  subnet_type    = "PRIVATE"
  usage_type     = "GEN"
}

# 로드밸런서 전용 서브넷(사설·공인). ALB Ingress Controller가 공인 LB를 만들 때 공인 LB 서브넷을 쓴다.
resource "ncloud_subnet" "lb_private" {
  name           = "${var.name}-lb-private"
  vpc_no         = ncloud_vpc.this.id
  subnet         = cidrsubnet(var.vpc_cidr, 8, 100)
  zone           = var.zone
  network_acl_no = ncloud_vpc.this.default_network_acl_no
  subnet_type    = "PRIVATE"
  usage_type     = "LOADB"
}

resource "ncloud_subnet" "lb_public" {
  name           = "${var.name}-lb-public"
  vpc_no         = ncloud_vpc.this.id
  subnet         = cidrsubnet(var.vpc_cidr, 8, 101)
  zone           = var.zone
  network_acl_no = ncloud_vpc.this.default_network_acl_no
  subnet_type    = "PUBLIC"
  usage_type     = "LOADB"
}

# NAT Gateway: 사설 노드가 이미지 레지스트리·LLM API 등 외부로 나갈 때 사용
resource "ncloud_subnet" "nat" {
  name           = "${var.name}-nat"
  vpc_no         = ncloud_vpc.this.id
  subnet         = cidrsubnet(var.vpc_cidr, 8, 200)
  zone           = var.zone
  network_acl_no = ncloud_vpc.this.default_network_acl_no
  subnet_type    = "PUBLIC"
  usage_type     = "NATGW"
}

resource "ncloud_nat_gateway" "this" {
  name      = "${var.name}-nat"
  vpc_no    = ncloud_vpc.this.id
  subnet_no = ncloud_subnet.nat.id
  zone      = var.zone
}

resource "ncloud_route" "private_default" {
  route_table_no         = ncloud_vpc.this.default_private_route_table_no
  destination_cidr_block = "0.0.0.0/0"
  target_type            = "NATGW"
  target_name            = ncloud_nat_gateway.this.name
  target_no              = ncloud_nat_gateway.this.id
}

resource "ncloud_login_key" "this" {
  key_name = "${var.name}-key"
}

data "ncloud_nks_versions" "this" {
  filter {
    name   = "value"
    values = [var.nks_version]
    regex  = true
  }
}

resource "ncloud_nks_cluster" "this" {
  name                 = "${var.name}-nks"
  cluster_type         = var.nks_cluster_type
  k8s_version          = data.ncloud_nks_versions.this.versions[0].value
  login_key_name       = ncloud_login_key.this.key_name
  vpc_no               = ncloud_vpc.this.id
  zone                 = var.zone
  subnet_no_list       = [ncloud_subnet.node.id]
  lb_private_subnet_no = ncloud_subnet.lb_private.id
  lb_public_subnet_no  = ncloud_subnet.lb_public.id
  kube_network_plugin  = "cilium" # NetworkPolicy 적용
  log {
    audit = true
  }
}

data "ncloud_nks_server_images" "this" {
  hypervisor_code = "KVM"
  filter {
    name   = "label"
    values = ["ubuntu-22.04"]
    regex  = true
  }
}

data "ncloud_nks_server_products" "this" {
  software_code = data.ncloud_nks_server_images.this.images[0].value
  zone          = var.zone
  filter {
    name   = "product_type"
    values = ["STAND"]
  }
  filter {
    name   = "cpu_count"
    values = [var.node_cpu]
  }
  filter {
    name   = "memory_size"
    values = [var.node_memory]
  }
}

resource "ncloud_nks_node_pool" "default" {
  cluster_uuid     = ncloud_nks_cluster.this.uuid
  node_pool_name   = "${var.name}-pool"
  node_count       = var.node_count
  software_code    = data.ncloud_nks_server_images.this.images[0].value
  server_spec_code = data.ncloud_nks_server_products.this.products[0].value
  storage_size     = 100
  subnet_no_list   = [ncloud_subnet.node.id]
  autoscale {
    enabled = true
    min     = var.node_count
    max     = var.node_max_count
  }
}

# 3D 모델 저장소(S3 호환). twin-api는 STORAGE_TYPE=s3로 이 버킷을 쓴다.
resource "ncloud_objectstorage_bucket" "models" {
  bucket_name = var.models_bucket
}

# PostgreSQL 일일 백업(infra/k8s/components/backup). 보관 기간 정리는 백업 Job이 직접 한다(BACKUP_RETENTION_DAYS).
resource "ncloud_objectstorage_bucket" "backups" {
  bucket_name = var.backups_bucket
}

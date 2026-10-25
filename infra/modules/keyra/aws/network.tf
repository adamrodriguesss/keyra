# The module VPC, unless egress.vpc_id deploys into yours. Two AZs: the load balancer and RDS need two.
data "aws_availability_zones" "available" {
  count = local.use_own_vpc ? 1 : 0
  state = "available"
}

locals {
  azs = local.use_own_vpc ? slice(data.aws_availability_zones.available[0].names, 0, 2) : []
}

resource "aws_vpc" "this" {
  count = local.use_own_vpc ? 1 : 0

  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = var.name }
}

# Public: the load balancer, and tasks unless static_ip moves them behind the NAT.
resource "aws_subnet" "public" {
  count = local.use_own_vpc ? 2 : 0

  vpc_id            = aws_vpc.this[0].id
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, count.index)
  availability_zone = local.azs[count.index]

  tags = { Name = "${var.name}-public-${local.azs[count.index]}" }
}

# Private: RDS, the database-init function, and tasks with static_ip.
resource "aws_subnet" "private" {
  count = local.use_own_vpc ? 2 : 0

  vpc_id            = aws_vpc.this[0].id
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, count.index + 8)
  availability_zone = local.azs[count.index]

  tags = { Name = "${var.name}-private-${local.azs[count.index]}" }
}

resource "aws_internet_gateway" "this" {
  count = local.use_own_vpc ? 1 : 0

  vpc_id = aws_vpc.this[0].id
  tags   = { Name = var.name }
}

resource "aws_route_table" "public" {
  count = local.use_own_vpc ? 1 : 0

  vpc_id = aws_vpc.this[0].id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this[0].id
  }
  tags = { Name = "${var.name}-public" }
}

resource "aws_route_table_association" "public" {
  count = local.use_own_vpc ? 2 : 0

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public[0].id
}

# Private subnets route out only through the NAT; without static_ip they have no internet route at all.
resource "aws_route_table" "private" {
  count = local.use_own_vpc ? 1 : 0

  vpc_id = aws_vpc.this[0].id

  dynamic "route" {
    for_each = local.use_nat ? [1] : []
    content {
      cidr_block     = "0.0.0.0/0"
      nat_gateway_id = aws_nat_gateway.egress[0].id
    }
  }

  tags = { Name = "${var.name}-private" }
}

resource "aws_route_table_association" "private" {
  count = local.use_own_vpc ? 2 : 0

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[0].id
}

# One NAT gateway in one AZ: the static IP is the point; if that AZ fails, egress fails with it.
resource "aws_eip" "egress" {
  count = local.use_nat ? 1 : 0

  domain = "vpc"
  tags   = { Name = "${var.name}-egress" }
}

resource "aws_nat_gateway" "egress" {
  count = local.use_nat ? 1 : 0

  allocation_id = aws_eip.egress[0].id
  subnet_id     = aws_subnet.public[0].id
  tags          = { Name = "${var.name}-egress" }

  depends_on = [aws_internet_gateway.this]
}

# Load balancer: HTTPS (and the HTTP redirect) from anywhere with a domain; without one, only CloudFront's VPC origin. Out only to the tasks.
resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "Keyra load balancer"
  vpc_id      = local.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "alb" {
  for_each = local.use_domain ? { http = 80, https = 443 } : { cloudfront = 80 }

  security_group_id            = aws_security_group.alb.id
  cidr_ipv4                    = local.use_domain ? "0.0.0.0/0" : null
  referenced_security_group_id = local.use_domain ? null : data.aws_security_group.cloudfront_vpc_origin[0].id
  ip_protocol                  = "tcp"
  from_port                    = each.value
  to_port                      = each.value
}

resource "aws_vpc_security_group_egress_rule" "alb_to_tasks" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.task.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

# Tasks: in only from the load balancer, so a public IP exposes nothing; out to anywhere (image, AWS APIs, database).
resource "aws_security_group" "task" {
  name        = "${var.name}-task"
  description = "Keyra tasks"
  vpc_id      = local.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "task_from_alb" {
  security_group_id            = aws_security_group.task.id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

resource "aws_vpc_security_group_egress_rule" "task" {
  security_group_id = aws_security_group.task.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

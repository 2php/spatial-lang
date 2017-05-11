0. Introduction
===============

Prerequisites
-------------

Running on EC2 FPGAs requires the following prerequisites:

- An installation of Spatial as described in the :doc:`previous tutorial <../tutorial/starting>`
- Vivado and a license to run on the desired Amazon FPGA. In our testing, we used Amazon's `FPGA Developer AMI <https://aws.amazon.com/marketplace/pp/B06VVYBLZZ#>`_ which contains all the required software tools.

Setup
-----

.. highlight:: bash

Clone Amazon's `EC2 FPGA Hardware and Software Development Kit <https://github.com/aws/aws-fpga/>`_ to any location::

    git clone https://github.com/aws/aws-fpga.git

Set the ``AWS_HOME`` environment variable to point to the cloned directory, e.g.::

    export AWS_HOME=/path/to/aws-fpga

Spatial supports the current master branch of this repository.

Finally, applications targeting the F1 board (in hardware or simulation) need to set the ``target`` variable. For example,
make the following change in the very top of the ``apps/src/MatMult_outer.scala`` application::

    object MatMult_outer extends SpatialApp {
      import IR._
      override val target = targets.AWS_F1  // <---- new line
      ...

The next tutorial sections describe how to generate and run applications for :doc:`simulation <sim>` and :doc:`for the FPGAs on the F1 instances<F1>`.

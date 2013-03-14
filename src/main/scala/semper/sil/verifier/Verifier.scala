
package semper.sil.verifier

import semper.sil.ast.Program

/** An abstract class for verifiers of SIL programs.
  *
  * The lifetime of a verifier is as follows.  After the object has been created, `commandLineArgs` is called
  * at most once to set the command line arguments.  Similarly for `debugInfo` which is also called
  * at most once, either before or after `commandLineArgs`.
  * Afterwards, one or more calls to `verify` follow.
  *
  * RFC: [Malte] If we made this an abstract class, then `commandLineArgs` and `debugInfo` could
  *      be constructor arguments and we wouldn't have to impose a protocol on clients.
  *
  * @author Stefan Heule
  */
trait Verifier {

  /** The name of this verifier (all-lowercase, to be used to uniquely identify this verifier). */
  def name: String

  /** Returns the version of the verifier. */
  def version: String

  /** Returns the copyright string of this verifier, e.g., "(c) 2013 Name" */
  def copyright: String

  /**
   * Set some debug information from the calling part of the system.
   *
   * Key-value pairs of information that could help during debugging. For example,
   * the full command line that was used to (indirectly, for instance, via a translator) start the
   * verifier.
   */
  def debugInfo(info: Seq[(String, Any)])

  /**
   * Returns the dependencies.  A dependency could be any library or stand-alone
   * tool that this verifier relies on, either directly or indirectly.  Typically only other
   * tools in the verification tool-chain are included here which can easily influence the
   * verification outcome.
   */
  def dependencies: Seq[Dependency]

  /** Set the command-line arguments to be used in this verifier. */
  def commandLineArgs(options: Seq[String])

  /** Verifies a given SIL program and returns a sequence of ''verification errors''.
    *
    * @param program The program to be verified.
    * @return The verification result.
    */
  def verify(program: Program): VerificationResult
}

/** A description of a dependency of a verifier. */
trait Dependency {
  /** The name of the dependency. */
  def name: String

  /** The version of this dependency. */
  def version: String

  /** The location of this dependency.  Typically a path. */
  def location: String
}

case class DefaultDependency(name: String, version: String, location: String) extends Dependency

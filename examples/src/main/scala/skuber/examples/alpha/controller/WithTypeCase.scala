package skuber.examples.alpha.controller

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Merge, Sink, Source}
import play.api.libs.json.Format
import shapeless._
import skuber.LabelSelector.IsEqualRequirement
import skuber._
import skuber.api.alpha.controller._
import skuber.json.format._
import skuber.json.ext.format._
import skuber.api.alpha.controller.ListWatch.ListWatchers
import skuber.api.client.LoggingContext
import skuber.ext.Deployment

import scala.concurrent.Future

object WithTypeCase extends App {

  implicit val system = ActorSystem()
  implicit val dispatcher = system.dispatcher
  implicit val materializer = ActorMaterializer()

  val k8s = k8sInit

  val `K8SWatchEvent[Pod]` = TypeCase[K8SWatchEvent[Pod]]
  val `K8SWatchEvent[Deployment]` = TypeCase[K8SWatchEvent[Deployment]]
  val `K8SWatchEvent[Foo]` = TypeCase[K8SWatchEvent[Foo]]

  val podsListWatcher = new ListWatchers[Pod](k8s)
  val deploymentsListWatcher = new ListWatchers[Deployment](k8s)
  val foosListWatcher = new ListWatchers[Foo](k8s)

  def getInNamespaceOption[O <: ObjectResource](name: String, namespace: String)(
    implicit fmt: Format[O], rd: ResourceDefinition[O], lc: LoggingContext
  ): Future[Option[O]] = {
    k8s.getInNamespace[O](name, namespace).map { result =>
      Some(result)
    } recover {
      case ex: K8SException if ex.status.code.contains(StatusCodes.NotFound.intValue) => None
    }
  }

  def createDeployment(f: Foo): Future[Deployment] = {
    val labels = Map(
      "app" -> "nginx",
      "controller" -> f.metadata.name
    )

    val container = Container(name = "nginx", image = "nginx:latest")

    val template = Pod.Template.Spec(metadata = ObjectMeta()).addLabels(labels).addContainer(container)

    val deployment = Deployment(metadata = ObjectMeta(
      name = f.spec.fold("foo")(_.deploymentName),
      namespace = f.metadata.namespace,
      ownerReferences = List(OwnerReference(
        apiVersion = f.apiVersion, kind = f.kind, name = f.metadata.name, uid = f.uid,
        controller = Some(true), blockOwnerDeletion = Some(true)
      ))
    )).withReplicas(f.spec.fold(1)(_.replicas))
      .withLabelSelector(LabelSelector(labels.map(x => IsEqualRequirement(x._1, x._2)).toList: _*))
      .withTemplate(template)

    k8s.create[Deployment](deployment)
  }

  def updateDeployment(f: Foo, deployment: Deployment): Future[Deployment] = {
    val updated = f.spec.fold(deployment) { spec =>
      deployment.withResourceVersion(f.metadata.resourceVersion)
        .copy(metadata = deployment.metadata.copy(name = spec.deploymentName))
        .withReplicas(spec.replicas)
    }

    k8s.update[Deployment](updated)
  }

  def reconcile(o: ObjectResource): ObjectResource = {
    o match {
      case f: Foo =>
        f.spec.foreach { spec =>
          getInNamespaceOption[Deployment](name = spec.deploymentName, namespace = f.metadata.namespace).map {
            case Some(d) => updateDeployment(f, d)
            case None => createDeployment(f)
          }
        }

        f
      case _ => o
    }
  }

  val podsReflector = new Reflector(podsListWatcher.listWatchAll)
  val deploymentsReflector = new Reflector(deploymentsListWatcher.listWatchAll)
  val foosReflector = new Reflector(foosListWatcher.listWatchAll)

  val podsCommands = Source.single(Reflector.DoReflect).via(podsReflector.commandFlow)
  val deploymentCommands = Source.single(Reflector.DoReflect).via(deploymentsReflector.commandFlow)
  val foosCommands = Source.single(Reflector.DoReflect).via(foosReflector.commandFlow)

  Source.combine(podsCommands, deploymentCommands, foosCommands)(Merge(_)).map {
    case Replacement(_, o) =>
      reconcile(o)
    case Updated(o) => reconcile(o)
    case _ =>
  }.runWith(Sink.foreach(println))
}
